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
        List<AiMeetingSttState> sttStates = sttStateRepository.findByMeetingIdOrderByChunkSeqAsc(meetingId);
        if (sttStates.isEmpty()) {
            throw new IllegalStateException("no chunks found. meetingId=" + meetingId);
        }

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
            return new AudioDownloadDto(
                "meeting_" + meetingId + "_merged.webm",
                "audio/webm",
                blob.getContent()
            );
        }

        Path tempDir = Files.createTempDirectory("stt-merge-" + meetingId + "-");
        try {
            List<Path> inputFiles = new ArrayList<>();

            for (AiMeetingSttState sttState : sttStates) {
                GcsPath path = parseGsUri(sttState.getGcsUri());
                Blob blob = storage.get(BlobId.of(path.getBucket(), path.getObject()));
                if (blob == null) {
                    throw new IllegalStateException("GCS blob not found. uri=" + sttState.getGcsUri());
                }

                Path localFile = tempDir.resolve("chunk_" + sttState.getChunkSeq() + ".webm");
                blob.downloadTo(localFile);
                inputFiles.add(localFile);
            }

            Path merged = tempDir.resolve("meeting_" + meetingId + "_merged.m4a");
            runFfmpegConcat(inputFiles, merged);

            return new AudioDownloadDto(
                "meeting_" + meetingId + "_merged.m4a",
                "audio/mp4",
                Files.readAllBytes(merged)
            );
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    private void runFfmpegConcat(List<Path> inputFiles, Path outputFile) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");

        for (Path input : inputFiles) {
            command.add("-i");
            command.add(input.toAbsolutePath().toString());
        }

        command.add("-filter_complex");
        command.add("concat=n=%d:v=0:a=1[outa]".formatted(inputFiles.size()));
        command.add("-map");
        command.add("[outa]");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-movflags");
        command.add("+faststart");
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
        try (FileInputStream in = new FileInputStream(apiKeyPath)) {
            return GoogleCredentials.fromStream(in)
                                    .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        }
    }

    private GcsPath parseGsUri(String gsUri) {
        if (gsUri == null || !gsUri.startsWith("gs://")) {
            throw new IllegalArgumentException("Not a gs:// uri: " + gsUri);
        }

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
