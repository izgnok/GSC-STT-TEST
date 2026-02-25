package com.example.stttest.web;

import com.example.stttest.dto.AudioDownloadDto;
import com.example.stttest.dto.rs.ChunkBatchUploadRs;
import com.example.stttest.dto.rs.ChunkUploadAutoRs;
import com.example.stttest.dto.rs.MeetingSnapshotRs;
import com.example.stttest.service.SttService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/stt")
public class SttController {

    private final SttService sttService;

    /**
     * 단일 청크 업로드 + STT 시작 (신규 미팅 자동 생성)
     */
    @PostMapping("/chunks")
    public ChunkUploadAutoRs uploadChunkNewMeeting(
        @RequestParam MultipartFile audioFile,
        @RequestParam(defaultValue = "ko-KR") String languageCode
    ) throws Exception {
        return sttService.uploadChunkAutoSeqNewMeeting(audioFile, languageCode);
    }

    /**
     * 다중 청크 업로드 + STT 시작 (신규 미팅 자동 생성)
     */
    @PostMapping("/chunks/batch")
    public ChunkBatchUploadRs uploadChunksNewMeeting(
        @RequestParam List<MultipartFile> audioFiles,
        @RequestParam(defaultValue = "ko-KR") String languageCode
    ) throws Exception {
        return sttService.uploadChunksAutoSeqNewMeeting(audioFiles, languageCode);
    }

    /**
     * 단일 청크 업로드 + STT 시작 (chunkSeq 자동)
     */
    @PostMapping("/meetings/{meetingId}/chunks")
    public ChunkUploadAutoRs uploadChunkAuto(
        @PathVariable Long meetingId,
        @RequestParam MultipartFile audioFile,
        @RequestParam(defaultValue = "ko-KR") String languageCode
    ) throws Exception {
        return sttService.uploadChunkAutoSeq(meetingId, audioFile, languageCode);
    }

    /**
     * 다중 청크 업로드 + STT 시작 (chunkSeq 자동)
     */
    @PostMapping("/meetings/{meetingId}/chunks/batch")
    public ChunkBatchUploadRs uploadChunksAuto(
        @PathVariable Long meetingId,
        @RequestParam List<MultipartFile> audioFiles,
        @RequestParam(defaultValue = "ko-KR") String languageCode
    ) throws Exception {
        return sttService.uploadChunksAutoSeq(meetingId, audioFiles, languageCode);
    }

    /**
     * 통합 상태 조회.
     * poll=true면 완료 상태를 먼저 점검하고, 결과(상태/대본/자막/청크목록)를 한 번에 반환한다.
     */
    @GetMapping("/meetings/{meetingId}/snapshot")
    public MeetingSnapshotRs getMeetingSnapshot(
        @PathVariable Long meetingId,
        @RequestParam(defaultValue = "false") boolean poll
    ) throws Exception {
        return sttService.getMeetingSnapshot(meetingId, poll);
    }

    /**
     * 회의 전체 병합 오디오 다운로드/재생
     */
    @GetMapping("/meetings/{meetingId}/audio/merged")
    public ResponseEntity<byte[]> downloadMergedMeetingAudio(@PathVariable Long meetingId) throws Exception {
        AudioDownloadDto dto = sttService.downloadMergedMeetingAudio(meetingId);
        return ResponseEntity.ok()
                             .header(HttpHeaders.CONTENT_DISPOSITION,
                                     "inline; filename=\"" + dto.getFileName() + "\"")
                             .contentType(MediaType.parseMediaType(dto.getContentType()))
                             .body(dto.getBytes());
    }
}
