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

    /**
     * GCS object 경로 날짜 포맷.
     * 예: 2026-02-25/meet_123/in/chunk_1.webm
     */
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
        // 업로드 요청 자체를 미팅 시작으로 보고 meetingId를 즉시 발급한다.
        Long meetingId = generateMeetingId();
        return uploadChunkAutoSeq(meetingId, audioFile, languageCode);
    }

    /**
     * 신규 미팅을 자동 발급한 뒤 다중 파일을 배치 업로드한다.
     */
    @Transactional
    public ChunkBatchUploadRs uploadChunksAutoSeqNewMeeting(List<MultipartFile> audioFiles,
                                                            String languageCode) throws Exception {
        // 배치 업로드도 동일하게 첫 요청 시 meetingId를 발급해 묶는다.
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
        // 같은 meetingId에서 가장 마지막 chunkSeq를 조회해 다음 순번을 계산한다.
        // 데이터가 하나도 없으면 첫 청크이므로 1부터 시작한다.
        int nextChunkSeq = sttStateRepository.findTopByMeetingIdOrderByChunkSeqDesc(meetingId)
                                             .map(s -> s.getChunkSeq() + 1)
                                             .orElse(1);

        // 실 업로드/작업 생성은 공통 메서드에 위임한다.
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

        // 입력 리스트 순서를 그대로 유지해야 청크 순번/재생 순서가 일치한다.
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
        // out/in 경로를 날짜 단위 prefix로 묶어 운영 시 정리/조회가 쉽도록 구성한다.
        String today = LocalDate.now().format(DATE_FORMAT);
        String objectName = "%s/meet_%s/in/chunk_%d.webm".formatted(today, meetingId, chunkSeq);

        // 자막 글로벌 오프셋 계산의 기준값은 서버(ffprobe) 측정 duration을 사용한다.
        // 프론트 메타데이터 값을 신뢰하면 누적 오차가 커질 수 있다.
        long probedDurationMs = audioDurationProbeService.probeWebmDurationMs(audioFile);

        // 1) 원본 청크 업로드
        String gcsUri = googleSttService.uploadToGcs(audioFile, objectName);
        // 2) 해당 청크에 대한 STT 비동기 작업 시작
        String jobId = googleSttService.startSttJob(gcsUri, languageCode, today, meetingId);

        // 3) 청크 상태를 DB에 PROCESSING으로 저장해 폴링 대상에 포함시킨다.
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
            // 아직 업로드된 청크가 없으면 기다림 상태를 반환한다.
            return MeetingCompleteRs.wait(meetingId);
        }

        for (AiMeetingSttState sttState : sttStates) {
            if (sttState.getStatus() == ChunkStatus.DONE) {
                // 이미 완료된 청크는 재처리하지 않는다.
                continue;
            }

            // Google long-running operation 상태를 조회한다.
            SttJobResultDto result = googleSttService.checkSttJobStatus(sttState.getJobId());
            switch (result.getStatus()) {
                case DONE -> {
                    // 완료 시 transcript + cue를 저장하고 상태를 DONE으로 고정한다.
                    sttState.setStatus(ChunkStatus.DONE);
                    sttState.setTranscript(result.getTranscript());
                    sttState.setErrorMessage(null);
                    saveChunkCues(sttState, result.getCues());
                    log.info("청크 처리 완료: meetingId={}, chunkSeq={}", meetingId, sttState.getChunkSeq());
                }
                case ERROR -> {
                    // 실패한 청크는 같은 입력 GCS URI로 job을 재등록한다.
                    String languageCode = (sttState.getLanguageCode() == null || sttState.getLanguageCode().isBlank())
                                          ? "ko-KR"
                                          : sttState.getLanguageCode();

                    String today = sttState.getCreatedDate().format(DATE_FORMAT);
                    String newJobId = googleSttService.startSttJob(sttState.getGcsUri(), languageCode, today, meetingId);

                    // 상태를 PROCESSING으로 되돌리고 바로 WAIT를 반환한다.
                    // 한 번의 요청에서 무한 재시작 루프를 만들지 않기 위함이다.
                    sttState.setJobId(newJobId);
                    sttState.setStatus(ChunkStatus.PROCESSING);
                    sttState.setErrorMessage(result.getErrorMessage());

                    log.warn("청크 에러 재시작: meetingId={}, chunkSeq={}, newJobId={}",
                             meetingId, sttState.getChunkSeq(), newJobId);
                    return MeetingCompleteRs.wait(meetingId);
                }
                case PROCESSING -> {
                    // 하나라도 진행 중이면 미팅 전체 완료가 아니므로 즉시 WAIT 반환.
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
            // DONE + transcript 존재 조건을 만족하는 청크만 병합한다.
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
        // 앞 청크의 실제 길이를 누적해 "회의 전체 타임라인" 오프셋을 만든다.
        long runningOffsetMs = 0L;
        List<SubtitleCueRs> cues = new ArrayList<>();

        for (AiMeetingSttState sttState : sttStates) {
            if (sttState.getStatus() != ChunkStatus.DONE) {
                // 완료되지 않은 청크 cue는 아직 글로벌 타임라인에 포함하지 않는다.
                continue;
            }
            completedChunks++;

            List<SubtitleCueRs> chunkCues = readChunkCues(sttState);
            for (SubtitleCueRs cue : chunkCues) {
                // chunk 로컬 시간(start/end)에 누적 오프셋을 더해 글로벌 시간으로 변환한다.
                cues.add(new SubtitleCueRs(
                    sttState.getChunkSeq(),
                    cue.getStartMs() + runningOffsetMs,
                    cue.getEndMs() + runningOffsetMs,
                    cue.getText(),
                    cue.getSpeaker()
                ));
            }

            // 다음 청크 보정값을 위해 현재 청크 실제 길이를 누적한다.
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

            // 클라이언트는 chunk마다 같은 merged audio endpoint를 사용한다.
            // 실제 재생은 청크 개별 파일이 아닌 병합 파일 기준으로 이뤄진다.
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
            // poll=true면 먼저 completeMeeting을 실행해 상태를 최신화한다.
            status = completeMeeting(meetingId).getStatus();
        } else {
            // poll=false면 조회만 수행하고, 현재 완료율로 상태를 계산한다.
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

        // 같은 chunkId에 대해 재저장될 수 있으므로 기존 cue를 먼저 삭제한다.
        chunkCueRepository.deleteByChunkId(sttState.getId());

        if (cues == null || cues.isEmpty()) {
            // transcript만 있고 cue가 비어있는 경우를 허용한다.
            return;
        }

        List<AiMeetingSttChunkCue> rows = new ArrayList<>(cues.size());
        int cueIndex = 1;

        for (SttCueDto cue : cues) {
            // cue_index는 정렬 보장을 위한 순차 번호다.
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

        // cue_index asc로 읽어 저장 순서를 그대로 유지한다.
        List<AiMeetingSttChunkCue> rows = chunkCueRepository.findByChunkIdOrderByCueIndexAsc(sttState.getId());
        if (rows.isEmpty()) {
            return List.of();
        }

        List<SubtitleCueRs> cues = new ArrayList<>(rows.size());
        for (AiMeetingSttChunkCue row : rows) {
            if (row == null || row.getStartMs() == null || row.getEndMs() == null) {
                // 비정상 row는 제외하고 나머지만 반환한다.
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
            // 글로벌 타임라인 계산의 필수값이므로 누락 시 즉시 실패시킨다.
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
        // ms timestamp(13자리) * 1000 + 난수(0~999) 조합.
        // 단일 인스턴스 환경에서 충돌 확률을 낮추는 용도다.
        return System.currentTimeMillis() * 1000L + ThreadLocalRandom.current().nextInt(1000);
    }
}
