package com.example.stttest.service;

import com.example.stttest.dto.stt.SttCueDto;
import com.example.stttest.dto.stt.SttJobResultDto;
import com.example.stttest.entitiy.ChunkStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v2.BatchRecognizeFileMetadata;
import com.google.cloud.speech.v2.BatchRecognizeFileResult;
import com.google.cloud.speech.v2.BatchRecognizeRequest;
import com.google.cloud.speech.v2.BatchRecognizeResponse;
import com.google.cloud.speech.v2.ExplicitDecodingConfig;
import com.google.cloud.speech.v2.GcsOutputConfig;
import com.google.cloud.speech.v2.NativeOutputFileFormatConfig;
import com.google.cloud.speech.v2.OutputFormatConfig;
import com.google.cloud.speech.v2.RecognitionConfig;
import com.google.cloud.speech.v2.RecognitionFeatures;
import com.google.cloud.speech.v2.RecognitionOutputConfig;
import com.google.cloud.speech.v2.SpeakerDiarizationConfig;
import com.google.cloud.speech.v2.SpeechClient;
import com.google.cloud.speech.v2.SpeechSettings;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsClient;
import com.google.protobuf.Any;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class GoogleSttService {

    @Value("${google.stt.projectId}")
    private String projectId;

    @Value("${google.stt.location:us-central1}")
    private String location;

    @Value("${google.stt.apiKeyPath}")
    private String apiKeyPath;

    @Value("${google.stt.bucket}")
    private String bucket;

    private static final Pattern FIRST_NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private final ObjectMapper objectMapper;

    @Getter
    @AllArgsConstructor
    private static class ParsedNativeResult {
        private final String transcript;
        private final List<WordSegment> wordSegments;
    }

    @Getter
    @AllArgsConstructor
    private static class WordSegment {
        private final long startMs;
        private final long endMs;
        private final String speaker;
        private final String word;
    }

    @Getter
    @AllArgsConstructor
    private static class GcsPath {
        private final String bucket;
        private final String object;
    }

    /**
     * 입력 파일 포맷은 webm-opus로 고정이므로 content-type도 audio/webm으로 고정 저장한다.
     */
    public String uploadToGcs(MultipartFile file, String objectName) throws Exception {
        GoogleCredentials creds = loadCreds();
        Storage storage = StorageOptions.newBuilder()
                                        .setCredentials(creds)
                                        .build()
                                        .getService();

        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                                    .setContentType("audio/webm")
                                    .build();

        storage.create(blobInfo, file.getBytes());
        return "gs://" + bucket + "/" + objectName;
    }

    /**
     * 고정 입력(webm-opus, 48k, mono) 기준으로 STT batchRecognize job을 시작한다.
     */
    public String startSttJob(String gcsUri, String languageCode, String today, Long meetingId) throws Exception {
        GoogleCredentials creds = loadCreds();
        SpeechSettings settings = newSpeechSettings(creds);

        try (SpeechClient client = SpeechClient.create(settings)) {
            SpeakerDiarizationConfig diarization = SpeakerDiarizationConfig.newBuilder()
                                                                           .setMinSpeakerCount(2)
                                                                           .setMaxSpeakerCount(6)
                                                                           .build();

            RecognitionFeatures features = RecognitionFeatures.newBuilder()
                                                              .setEnableWordTimeOffsets(true)
                                                              .setDiarizationConfig(diarization)
                                                              .build();

            ExplicitDecodingConfig explicitDecodingConfig = ExplicitDecodingConfig.newBuilder()
                                                                                  .setEncoding(ExplicitDecodingConfig.AudioEncoding.WEBM_OPUS)
                                                                                  .setSampleRateHertz(48000)
                                                                                  .setAudioChannelCount(1)
                                                                                  .build();

            RecognitionConfig config = RecognitionConfig.newBuilder()
                                                        .setModel("chirp_3")
                                                        .setExplicitDecodingConfig(explicitDecodingConfig)
                                                        .addLanguageCodes(languageCode)
                                                        .setFeatures(features)
                                                        .build();

            String outputUri = "gs://%s/%s/meet_%s/out/".formatted(bucket, today, meetingId);

            RecognitionOutputConfig outConfig = RecognitionOutputConfig.newBuilder()
                                                                       .setGcsOutputConfig(
                                                                           GcsOutputConfig.newBuilder().setUri(outputUri).build()
                                                                       )
                                                                       .setOutputFormatConfig(
                                                                           OutputFormatConfig.newBuilder()
                                                                                             .setNative(NativeOutputFileFormatConfig.newBuilder().build())
                                                                                             .build()
                                                                       )
                                                                       .build();

            BatchRecognizeRequest request = BatchRecognizeRequest.newBuilder()
                                                                 .setRecognizer(
                                                                     "projects/%s/locations/%s/recognizers/_".formatted(projectId, location)
                                                                 )
                                                                 .setConfig(config)
                                                                 .addFiles(BatchRecognizeFileMetadata.newBuilder().setUri(gcsUri).build())
                                                                 .setRecognitionOutputConfig(outConfig)
                                                                 .setProcessingStrategy(
                                                                     BatchRecognizeRequest.ProcessingStrategy.PROCESSING_STRATEGY_UNSPECIFIED
                                                                 )
                                                                 .build();

            return client.batchRecognizeOperationCallable().futureCall(request).getName();
        }
    }

    /**
     * STT job을 조회해 DONE이면 native JSON을 읽어 transcript/cue를 만든다.
     *
     * 결과 포맷은 고정으로 가정하고, 불필요한 우회 분기 없이 fail-fast로 처리한다.
     */
    public SttJobResultDto checkSttJobStatus(String jobId) throws Exception {
        GoogleCredentials creds = loadCreds();
        SpeechSettings settings = newSpeechSettings(creds);
        Storage storage = StorageOptions.newBuilder()
                                        .setCredentials(creds)
                                        .build()
                                        .getService();

        try (SpeechClient client = SpeechClient.create(settings)) {
            OperationsClient ops = client.getOperationsClient();
            Operation op = ops.getOperation(jobId);

            if (!op.getDone()) {
                return new SttJobResultDto(ChunkStatus.PROCESSING, null, List.of(), null);
            }

            if (op.hasError()) {
                return new SttJobResultDto(ChunkStatus.ERROR, null, List.of(), op.getError().getMessage());
            }

            Any respAny = op.getResponse();
            BatchRecognizeResponse resp = respAny.unpack(BatchRecognizeResponse.class);

            StringBuilder transcriptSb = new StringBuilder();
            List<SttCueDto> cues = new ArrayList<>();

            for (BatchRecognizeFileResult fileResult : resp.getResultsMap().values()) {
                if (fileResult.hasError()) {
                    return new SttJobResultDto(ChunkStatus.ERROR, null, List.of(), fileResult.getError().getMessage());
                }

                String nativeUri = fileResult.getCloudStorageResult().getUri();
                ParsedNativeResult parsed = readNativeResultFromUri(nativeUri, storage);

                if (transcriptSb.length() > 0) {
                    transcriptSb.append('\n');
                }
                transcriptSb.append(parsed.getTranscript());

                cues.addAll(buildCuesFromWordSegments(parsed.getWordSegments()));
            }

            return new SttJobResultDto(ChunkStatus.DONE, transcriptSb.toString().trim(), cues, null);
        }
    }

    /**
     * cloudStorageResult.uri(native JSON)를 직접 읽는다.
     */
    private ParsedNativeResult readNativeResultFromUri(String nativeUri, Storage storage) throws Exception {
        GcsPath path = parseGsUri(nativeUri);
        Blob blob = storage.get(BlobId.of(path.getBucket(), path.getObject()));
        if (blob == null) {
            throw new IllegalStateException("native result blob not found. uri=" + nativeUri);
        }
        return parseNativeJson(blob.getContent());
    }

    /**
     * 고정 JSON 스키마 기준 파싱:
     * - results[*].alternatives[0].words[*]
     * - word / speakerLabel / startOffset / endOffset
     */
    private ParsedNativeResult parseNativeJson(byte[] bytes) throws Exception {
        JsonNode results = objectMapper.readTree(bytes).path("results");

        StringBuilder transcriptSb = new StringBuilder();
        List<WordSegment> wordSegments = new ArrayList<>();

        for (JsonNode resultNode : results) {
            JsonNode alt0 = resultNode.path("alternatives").get(0);
            JsonNode words = alt0.path("words");

            String currentSpeaker = null;
            StringBuilder currentLine = new StringBuilder();
            long prevEndMs = -1L;
            List<WordSegment> pendingUntimedWords = new ArrayList<>();

            for (JsonNode wordNode : words) {
                String word = wordNode.path("word").asText().trim();
                String normalizedSpeaker = normalizeSpeakerTag(wordNode.path("speakerLabel").asText());
                String speaker = normalizedSpeaker.isBlank()
                                 ? (currentSpeaker == null ? "0" : currentSpeaker)
                                 : normalizedSpeaker;

                Long startMs = parseDurationToMsOrNull(wordNode.path("startOffset").asText(null));
                Long endMs = parseDurationToMsOrNull(wordNode.path("endOffset").asText(null));

                if (startMs == null && endMs == null && prevEndMs < 0) {
                    // 선행 단어에 타임오프셋이 비어 있으면, 첫 timed 단어를 만날 때 직전 구간으로 보정한다.
                    pendingUntimedWords.add(new WordSegment(0L, 0L, speaker, word));
                } else {
                    if (startMs == null) {
                        startMs = (prevEndMs >= 0) ? prevEndMs : Math.max(0L, endMs - 1L);
                    }
                    if (endMs == null) {
                        endMs = startMs + 1L;
                    }
                    if (endMs <= startMs) {
                        endMs = startMs + 1L;
                    }

                    if (!pendingUntimedWords.isEmpty()) {
                        long backfillStart = Math.max(0L, startMs - pendingUntimedWords.size());
                        for (int i = 0; i < pendingUntimedWords.size(); i++) {
                            WordSegment pending = pendingUntimedWords.get(i);
                            long pendingStart = backfillStart + i;
                            long pendingEnd = pendingStart + 1L;
                            wordSegments.add(new WordSegment(
                                pendingStart,
                                pendingEnd,
                                pending.getSpeaker(),
                                pending.getWord()
                            ));
                        }
                        pendingUntimedWords.clear();
                    }

                    wordSegments.add(new WordSegment(startMs, endMs, speaker, word));
                    prevEndMs = endMs;
                }

                if (currentSpeaker == null) {
                    currentSpeaker = speaker;
                    currentLine.append("SPEAKER_").append(currentSpeaker).append(": ").append(word);
                    continue;
                }

                if (!speaker.equals(currentSpeaker)) {
                    appendTranscriptLine(transcriptSb, currentLine.toString());
                    currentSpeaker = speaker;
                    currentLine.setLength(0);
                    currentLine.append("SPEAKER_").append(currentSpeaker).append(": ").append(word);
                    continue;
                }

                currentLine.append(' ').append(word);
            }

            appendTranscriptLine(transcriptSb, currentLine.toString());
        }

        return new ParsedNativeResult(transcriptSb.toString().trim(), wordSegments);
    }

    /**
     * cue 분리 규칙:
     * 1) 화자 변경
     * 2) 단어가 '.'으로 끝남
     */
    private List<SttCueDto> buildCuesFromWordSegments(List<WordSegment> wordSegments) {
        wordSegments.sort(Comparator.comparingLong(WordSegment::getStartMs).thenComparingLong(WordSegment::getEndMs));

        List<SttCueDto> cues = new ArrayList<>();

        String currentSpeaker = null;
        long currentStartMs = 0L;
        long currentEndMs = 0L;
        StringBuilder cueText = new StringBuilder();

        for (WordSegment segment : wordSegments) {
            if (currentSpeaker == null) {
                currentSpeaker = segment.getSpeaker();
                currentStartMs = segment.getStartMs();
                currentEndMs = segment.getEndMs();
            } else if (!currentSpeaker.equals(segment.getSpeaker())) {
                cues.add(new SttCueDto(currentStartMs, currentEndMs, cueText.toString().trim(), currentSpeaker));
                currentSpeaker = segment.getSpeaker();
                currentStartMs = segment.getStartMs();
                currentEndMs = segment.getEndMs();
                cueText.setLength(0);
            } else {
                currentEndMs = Math.max(currentEndMs, segment.getEndMs());
            }

            if (cueText.length() > 0) {
                cueText.append(' ');
            }
            cueText.append(segment.getWord());

            if (segment.getWord().endsWith(".")) {
                cues.add(new SttCueDto(currentStartMs, currentEndMs, cueText.toString().trim(), currentSpeaker));
                currentSpeaker = null;
                cueText.setLength(0);
            }
        }

        if (currentSpeaker != null) {
            cues.add(new SttCueDto(currentStartMs, currentEndMs, cueText.toString().trim(), currentSpeaker));
        }

        return cues;
    }

    private void appendTranscriptLine(StringBuilder transcriptSb, String line) {
        if (transcriptSb.length() > 0) {
            transcriptSb.append('\n');
        }
        transcriptSb.append(line.trim());
    }

    private Long parseDurationToMsOrNull(String durationText) {
        if (durationText == null || durationText.isBlank()) {
            return null;
        }
        String trimmed = durationText.endsWith("s")
                         ? durationText.substring(0, durationText.length() - 1)
                         : durationText;
        return Math.round(Double.parseDouble(trimmed) * 1000d);
    }

    private String normalizeSpeakerTag(String rawSpeakerLabel) {
        Matcher numberMatcher = FIRST_NUMBER_PATTERN.matcher(rawSpeakerLabel);
        if (numberMatcher.find()) {
            return numberMatcher.group(1);
        }
        return rawSpeakerLabel;
    }

    private SpeechSettings newSpeechSettings(GoogleCredentials creds) throws Exception {
        return SpeechSettings.newBuilder()
                             .setEndpoint(location + "-speech.googleapis.com:443")
                             .setCredentialsProvider(FixedCredentialsProvider.create(creds))
                             .build();
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
}
