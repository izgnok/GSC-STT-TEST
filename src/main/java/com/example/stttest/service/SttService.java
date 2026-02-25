package com.example.stttest.service;

import com.example.stttest.dto.AudioDownloadDto;
import com.example.stttest.dto.rs.ChunkBatchUploadRs;
import com.example.stttest.dto.rs.ChunkUploadAutoRs;
import com.example.stttest.dto.rs.ChunkUploadRs;
import com.example.stttest.dto.rs.MeetingChunkRs;
import com.example.stttest.dto.rs.MeetingChunksRs;
import com.example.stttest.dto.rs.MeetingCompleteRs;
import com.example.stttest.dto.rs.MeetingSnapshotRs;
import com.example.stttest.dto.rs.MeetingSubtitleRs;
import com.example.stttest.dto.rs.MeetingTranscriptRs;
import com.example.stttest.dto.rs.SubtitleCueRs;
import com.example.stttest.dto.stt.SttCueDto;
import com.example.stttest.dto.stt.SttJobResultDto;
import com.example.stttest.entitiy.AiMeetingSttChunkCue;
import com.example.stttest.entitiy.AiMeetingSttState;
import com.example.stttest.entitiy.ChunkStatus;
import com.example.stttest.repository.AiMeetingSttChunkCueRepository;
import com.example.stttest.repository.AiMeetingSttStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SttService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AiMeetingSttStateRepository sttStateRepository;
    private final AiMeetingSttChunkCueRepository chunkCueRepository;
    private final GoogleSttService googleSttService;
    private final AudioDurationProbeService audioDurationProbeService;
    private final MeetingAudioMergeService meetingAudioMergeService;

    /**
     * 신규 미팅을 자동 발급한 뒤 단일 파일을 업로드한다.
     *
     * 컨트롤러에서 별도 \"미팅 생성\" API를 없애고,
     * 업로드 요청 자체가 미팅 시작점이 되도록 만든 메서드다.
     */
    @Transactional
    public ChunkUploadAutoRs uploadChunkAutoSeqNewMeeting(MultipartFile audioFile,
                                                          String languageCode) throws Exception {
        Long meetingId = generateMeetingId();
        return uploadChunkAutoSeq(meetingId, audioFile, languageCode);
    }

    /**
     * 신규 미팅을 자동 발급한 뒤 다중 파일을 배치 업로드한다.
     */
    @Transactional
    public ChunkBatchUploadRs uploadChunksAutoSeqNewMeeting(List<MultipartFile> audioFiles,
                                                            String languageCode) throws Exception {
        Long meetingId = generateMeetingId();
        return uploadChunksAutoSeq(meetingId, audioFiles, languageCode);
    }

    /**
     * 단일 파일 업로드 시 chunkSeq를 자동으로 계산해 저장/전송한다.
     */
    @Transactional
    public ChunkUploadAutoRs uploadChunkAutoSeq(Long meetingId,
                                                MultipartFile audioFile,
                                                String languageCode) throws Exception {
        int nextChunkSeq = sttStateRepository.findTopByMeetingIdOrderByChunkSeqDesc(meetingId)
                                             .map(s -> s.getChunkSeq() + 1)
                                             .orElse(1);

        ChunkUploadRs rs = uploadChunk(meetingId, nextChunkSeq, audioFile, languageCode);
        return new ChunkUploadAutoRs(rs.getMeetingId(), rs.getChunkSeq(), rs.getJobId(), rs.getGcsUri());
    }

    /**
     * 다중 파일 업로드 시 입력 순서를 그대로 chunkSeq에 반영한다.
     */
    @Transactional
    public ChunkBatchUploadRs uploadChunksAutoSeq(Long meetingId,
                                                  List<MultipartFile> audioFiles,
                                                  String languageCode) throws Exception {
        List<ChunkUploadAutoRs> out = new ArrayList<>();

        for (MultipartFile audioFile : audioFiles) {
            out.add(uploadChunkAutoSeq(meetingId, audioFile, languageCode));
        }

        return new ChunkBatchUploadRs(meetingId, out.size(), out);
    }

    /**
     * 1) GCS 업로드
     * 2) Google STT job 시작
     * 3) 상태 테이블에 PROCESSING으로 저장
     */
    @Transactional
    public ChunkUploadRs uploadChunk(Long meetingId,
                                     Integer chunkSeq,
                                     MultipartFile audioFile,
                                     String languageCode) throws Exception {
        String today = LocalDate.now().format(DATE_FORMAT);
        String objectName = "%s/meet_%s/in/chunk_%d.webm".formatted(today, meetingId, chunkSeq);
        long probedDurationMs = audioDurationProbeService.probeWebmDurationMs(audioFile);

        String gcsUri = googleSttService.uploadToGcs(audioFile, objectName);
        String jobId = googleSttService.startSttJob(gcsUri, languageCode, today, meetingId);

        AiMeetingSttState sttState = AiMeetingSttState.builder()
                                                      .meetingId(meetingId)
                                                      .chunkSeq(chunkSeq)
                                                      .gcsUri(gcsUri)
                                                      .jobId(jobId)
                                                      .durationMs(probedDurationMs)
                                                      .status(ChunkStatus.PROCESSING)
                                                      .languageCode(languageCode)
                                                      .createdDate(LocalDate.now())
                                                      .build();
        sttStateRepository.save(sttState);

        log.info("청크 업로드/STT 시작 완료: meetingId={}, chunkSeq={}, jobId={}", meetingId, chunkSeq, jobId);
        return new ChunkUploadRs(meetingId, chunkSeq, jobId, gcsUri);
    }

    /**
     * poll 구간에서 호출되는 완료 처리 메서드.
     *
     * - DONE: 대본/cue 저장
     * - ERROR: job 재등록 후 WAIT
     * - PROCESSING: 즉시 WAIT
     */
    @Transactional
    public MeetingCompleteRs completeMeeting(Long meetingId) throws Exception {
        List<AiMeetingSttState> sttStates = sttStateRepository.findByMeetingIdOrderByChunkSeqAsc(meetingId);
        if (sttStates.isEmpty()) {
            return MeetingCompleteRs.wait(meetingId);
        }

        for (AiMeetingSttState sttState : sttStates) {
            if (sttState.getStatus() == ChunkStatus.DONE) {
                continue;
            }

            SttJobResultDto result = googleSttService.checkSttJobStatus(sttState.getJobId());
            switch (result.getStatus()) {
                case DONE -> {
                    sttState.setStatus(ChunkStatus.DONE);
                    sttState.setTranscript(result.getTranscript());
                    sttState.setErrorMessage(null);
                    saveChunkCues(sttState, result.getCues());
                    log.info("청크 처리 완료: meetingId={}, chunkSeq={}", meetingId, sttState.getChunkSeq());
                }
                case ERROR -> {
                    String languageCode = (sttState.getLanguageCode() == null || sttState.getLanguageCode().isBlank())
                                          ? "ko-KR"
                                          : sttState.getLanguageCode();

                    String today = sttState.getCreatedDate().format(DATE_FORMAT);
                    String newJobId = googleSttService.startSttJob(sttState.getGcsUri(), languageCode, today, meetingId);

                    sttState.setJobId(newJobId);
                    sttState.setStatus(ChunkStatus.PROCESSING);
                    sttState.setErrorMessage(result.getErrorMessage());

                    log.warn("청크 에러 재시작: meetingId={}, chunkSeq={}, newJobId={}",
                             meetingId, sttState.getChunkSeq(), newJobId);
                    return MeetingCompleteRs.wait(meetingId);
                }
                case PROCESSING -> {
                    log.info("청크 처리 중: meetingId={}, chunkSeq={}", meetingId, sttState.getChunkSeq());
                    return MeetingCompleteRs.wait(meetingId);
                }
            }
        }

        log.info("모든 청크 처리 완료: meetingId={}", meetingId);
        return MeetingCompleteRs.done(meetingId);
    }

    /**
     * DONE 상태 청크 transcript를 chunkSeq 순으로 합친다.
     */
    public MeetingTranscriptRs getTranscript(Long meetingId) {
        List<AiMeetingSttState> sttStates = sttStateRepository.findByMeetingIdOrderByChunkSeqAsc(meetingId);
        if (sttStates.isEmpty()) {
            return new MeetingTranscriptRs(meetingId, null, 0, 0);
        }

        StringBuilder fullTranscript = new StringBuilder();
        int completedChunks = 0;

        for (AiMeetingSttState sttState : sttStates) {
            if (sttState.getStatus() == ChunkStatus.DONE && sttState.getTranscript() != null) {
                if (!fullTranscript.isEmpty()) {
                    fullTranscript.append('\n');
                }
                fullTranscript.append(sttState.getTranscript());
                completedChunks++;
            }
        }

        return new MeetingTranscriptRs(
            meetingId,
            fullTranscript.toString().trim(),
            sttStates.size(),
            completedChunks
        );
    }

    /**
     * chunk_cue 테이블에 저장된 로컬 타임라인(start/end)을 회의 글로벌 타임라인으로 보정해 반환한다.
     */
    public MeetingSubtitleRs getSubtitles(Long meetingId) {
        List<AiMeetingSttState> sttStates = sttStateRepository.findByMeetingIdOrderByChunkSeqAsc(meetingId);
        if (sttStates.isEmpty()) {
            return new MeetingSubtitleRs(meetingId, 0, 0, List.of());
        }

        int completedChunks = 0;
        long runningOffsetMs = 0L;
        List<SubtitleCueRs> cues = new ArrayList<>();

        for (AiMeetingSttState sttState : sttStates) {
            if (sttState.getStatus() != ChunkStatus.DONE) {
                continue;
            }
            completedChunks++;

            List<SubtitleCueRs> chunkCues = readChunkCues(sttState);
            for (SubtitleCueRs cue : chunkCues) {
                cues.add(new SubtitleCueRs(
                    sttState.getChunkSeq(),
                    cue.getStartMs() + runningOffsetMs,
                    cue.getEndMs() + runningOffsetMs,
                    cue.getText(),
                    cue.getSpeaker()
                ));
            }

            runningOffsetMs += resolveChunkDurationMs(sttState);
        }

        return new MeetingSubtitleRs(meetingId, sttStates.size(), completedChunks, cues);
    }

    /**
     * 프론트 호환용 청크 목록 응답.
     */
    public MeetingChunksRs getMeetingChunks(Long meetingId) {
        List<AiMeetingSttState> sttStates = sttStateRepository.findByMeetingIdOrderByChunkSeqAsc(meetingId);
        if (sttStates.isEmpty()) {
            return new MeetingChunksRs(meetingId, 0, 0, List.of());
        }

        int completed = 0;
        List<MeetingChunkRs> chunks = new ArrayList<>();

        for (AiMeetingSttState sttState : sttStates) {
            if (sttState.getStatus() == ChunkStatus.DONE) {
                completed++;
            }

            chunks.add(new MeetingChunkRs(
                sttState.getChunkSeq(),
                sttState.getStatus(),
                "/api/stt/meetings/%d/audio/merged".formatted(meetingId),
                sttState.getTranscript()
            ));
        }

        return new MeetingChunksRs(meetingId, sttStates.size(), completed, chunks);
    }

    /**
     * 컨트롤러가 한 번에 호출하는 통합 API 용 응답 조합 메서드.
     * poll=true인 경우 completeMeeting을 먼저 실행해 최신 상태를 반영한다.
     */
    @Transactional
    public MeetingSnapshotRs getMeetingSnapshot(Long meetingId, boolean poll) throws Exception {
        String status;
        if (poll) {
            status = completeMeeting(meetingId).getStatus();
        } else {
            MeetingChunksRs chunksRs = getMeetingChunks(meetingId);
            boolean allDone = chunksRs.getTotalChunks() > 0
                              && chunksRs.getTotalChunks().equals(chunksRs.getCompletedChunks());
            status = allDone ? "DONE" : "WAIT";
        }

        MeetingChunksRs chunksRs = getMeetingChunks(meetingId);
        MeetingTranscriptRs transcriptRs = getTranscript(meetingId);
        MeetingSubtitleRs subtitleRs = getSubtitles(meetingId);

        return new MeetingSnapshotRs(
            meetingId,
            status,
            chunksRs.getTotalChunks(),
            chunksRs.getCompletedChunks(),
            transcriptRs.getTranscript(),
            subtitleRs.getCues(),
            chunksRs.getChunks()
        );
    }

    /**
     * 오디오 병합/다운로드는 ffmpeg 전담 서비스로 위임한다.
     */
    public AudioDownloadDto downloadMergedMeetingAudio(Long meetingId) throws Exception {
        return meetingAudioMergeService.downloadMergedMeetingAudio(meetingId);
    }

    private void saveChunkCues(AiMeetingSttState sttState, List<SttCueDto> cues) {
        if (sttState == null || sttState.getId() == null) {
            return;
        }

        chunkCueRepository.deleteByChunkId(sttState.getId());

        if (cues == null || cues.isEmpty()) {
            return;
        }

        List<AiMeetingSttChunkCue> rows = new ArrayList<>(cues.size());
        int cueIndex = 1;

        for (SttCueDto cue : cues) {
            rows.add(AiMeetingSttChunkCue.builder()
                                         .meetingId(sttState.getMeetingId())
                                         .chunkId(sttState.getId())
                                         .chunkSeq(sttState.getChunkSeq())
                                         .cueIndex(cueIndex++)
                                         .startMs(cue.getStartMs())
                                         .endMs(cue.getEndMs())
                                         .text(cue.getText() == null ? "" : cue.getText())
                                         .speaker(cue.getSpeaker())
                                         .build());
        }

        chunkCueRepository.saveAll(rows);
    }

    private List<SubtitleCueRs> readChunkCues(AiMeetingSttState sttState) {
        if (sttState.getId() == null) {
            return List.of();
        }

        List<AiMeetingSttChunkCue> rows = chunkCueRepository.findByChunkIdOrderByCueIndexAsc(sttState.getId());
        if (rows.isEmpty()) {
            return List.of();
        }

        List<SubtitleCueRs> cues = new ArrayList<>(rows.size());
        for (AiMeetingSttChunkCue row : rows) {
            if (row == null || row.getStartMs() == null || row.getEndMs() == null) {
                continue;
            }
            cues.add(new SubtitleCueRs(
                row.getChunkSeq(),
                row.getStartMs(),
                row.getEndMs(),
                row.getText(),
                row.getSpeaker()
            ));
        }
        return cues;
    }

    private long resolveChunkDurationMs(AiMeetingSttState sttState) {
        if (sttState.getDurationMs() == null || sttState.getDurationMs() <= 0L) {
            throw new IllegalStateException(
                "durationMs is required. meetingId=%d, chunkSeq=%d"
                    .formatted(sttState.getMeetingId(), sttState.getChunkSeq())
            );
        }
        return sttState.getDurationMs();
    }

    /**
     * 외부 미팅 서버 없이도 충돌 확률을 낮추기 위한 로컬 meetingId 생성 규칙.
     */
    private Long generateMeetingId() {
        return System.currentTimeMillis() * 1000L + ThreadLocalRandom.current().nextInt(1000);
    }
}
