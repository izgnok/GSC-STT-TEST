package com.example.stttest.service;

import com.example.stttest.dto.AudioDownloadDto;
import com.example.stttest.entitiy.AiMeetingSttState;
import com.example.stttest.repository.AiMeetingSttStateRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class MeetingAudioMergeService {

    @Value("${google.stt.apiKeyPath}")
    private String apiKeyPath;

    private final AiMeetingSttStateRepository sttStateRepository;

    @Getter
    @AllArgsConstructor
    private static class GcsPath {
        private final String bucket;
        private final String object;
    }

    /**
     * 입력 청크는 webm-opus로 고정되어 있다고 가정한다.
     */
    public AudioDownloadDto downloadMergedMeetingAudio(Long meetingId) throws Exception {
        // 업로드 순서(=chunkSeq asc)대로 병합해야 자막 글로벌 타임라인과 일치한다.
        List<AiMeetingSttState> sttStates = sttStateRepository.findByMeetingIdOrderByChunkSeqAsc(meetingId);
        if (sttStates.isEmpty()) {
            throw new IllegalStateException("no chunks found. meetingId=" + meetingId);
        }

        // GCS에 저장된 청크 원본을 읽기 위한 인증/스토리지 클라이언트 준비.
        GoogleCredentials creds = loadCreds();
        Storage storage = StorageOptions.newBuilder()
                                        .setCredentials(creds)
                                        .build()
                                        .getService();

        // 청크가 하나면 그대로 webm을 반환한다.
        if (sttStates.size() == 1) {
            AiMeetingSttState only = sttStates.get(0);
            GcsPath path = parseGsUri(only.getGcsUri());
            Blob blob = storage.get(BlobId.of(path.getBucket(), path.getObject()));
            if (blob == null) {
                throw new IllegalStateException("GCS blob not found. uri=" + only.getGcsUri());
            }
            // 단일 청크는 병합 과정 없이 원본 bytes를 그대로 반환한다.
            return new AudioDownloadDto(
                "meeting_" + meetingId + "_merged.webm",
                "audio/webm",
                blob.getContent()
            );
        }

        // 다중 청크 병합은 임시 디렉터리에서 수행하고 finally에서 정리한다.
        Path tempDir = Files.createTempDirectory("stt-merge-" + meetingId + "-");
        try {
            List<Path> inputFiles = new ArrayList<>();

            for (AiMeetingSttState sttState : sttStates) {
                // DB의 gsUri를 파싱해 각 청크를 로컬 임시 파일로 내려받는다.
                GcsPath path = parseGsUri(sttState.getGcsUri());
                Blob blob = storage.get(BlobId.of(path.getBucket(), path.getObject()));
                if (blob == null) {
                    throw new IllegalStateException("GCS blob not found. uri=" + sttState.getGcsUri());
                }

                Path localFile = tempDir.resolve("chunk_" + sttState.getChunkSeq() + ".webm");
                blob.downloadTo(localFile);
                inputFiles.add(localFile);
            }

            // concat demuxer + copy 방식으로 코덱 재인코딩 없이 병합한다.
            Path merged = tempDir.resolve("meeting_" + meetingId + "_merged.webm");
            runFfmpegConcatWebm(inputFiles, tempDir.resolve("concat-inputs.txt"), merged);

            return new AudioDownloadDto(
                "meeting_" + meetingId + "_merged.webm",
                "audio/webm",
                Files.readAllBytes(merged)
            );
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    /**
     * concat demuxer + stream copy로 병합한다.
     *
     * - 재인코딩을 피해서 청크 경계 지연/패딩 누적 가능성을 줄인다.
     * - 입력은 동일 코덱(webm/opus)이라는 전제에서 동작한다.
     */
    private void runFfmpegConcatWebm(List<Path> inputFiles, Path concatListFile, Path outputFile) throws Exception {
        // ffmpeg concat demuxer 입력 파일(list file) 생성.
        // 형식:
        // file '/abs/path/chunk_1.webm'
        // file '/abs/path/chunk_2.webm'
        StringBuilder listText = new StringBuilder();
        for (Path input : inputFiles) {
            listText.append("file '")
                    .append(input.toAbsolutePath().toString().replace("'", "'\\''"))
                    .append("'\n");
        }
        Files.writeString(concatListFile, listText.toString(), StandardCharsets.UTF_8);

        // -c copy: 오디오 재인코딩을 생략해 경계 지연/패딩 누적 가능성을 줄인다.
        // -fflags +genpts: concat 후 PTS가 비어있을 때 timestamp를 재생성한다.
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");
        command.add("-f");
        command.add("concat");
        command.add("-safe");
        command.add("0");
        command.add("-i");
        command.add(concatListFile.toAbsolutePath().toString());
        command.add("-c");
        command.add("copy");
        command.add("-fflags");
        command.add("+genpts");
        command.add(outputFile.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();

        String ffmpegOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("ffmpeg merge failed. exitCode=%d, output=%s".formatted(exitCode, ffmpegOutput));
        }
    }

    private GoogleCredentials loadCreds() throws Exception {
        // 서비스 계정 키 파일 기반 인증.
        try (FileInputStream in = new FileInputStream(apiKeyPath)) {
            return GoogleCredentials.fromStream(in)
                                    .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        }
    }

    private GcsPath parseGsUri(String gsUri) {
        // 입력 형식 검증.
        if (gsUri == null || !gsUri.startsWith("gs://")) {
            throw new IllegalArgumentException("Not a gs:// uri: " + gsUri);
        }

        // gs://bucket/object 파싱.
        String rest = gsUri.substring("gs://".length());
        int idx = rest.indexOf('/');
        if (idx < 1 || idx == rest.length() - 1) {
            throw new IllegalArgumentException("Invalid gs:// uri: " + gsUri);
        }

        return new GcsPath(rest.substring(0, idx), rest.substring(idx + 1));
    }

    private void deleteDirectoryQuietly(Path path) {
        if (path == null) {
            return;
        }

        // 하위 파일/폴더를 역순으로 순회해야 디렉터리 삭제가 성공한다.
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {
        }
    }
}
