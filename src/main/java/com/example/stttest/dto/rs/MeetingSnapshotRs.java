package com.example.stttest.dto.rs;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class MeetingSnapshotRs {
    private Long meetingId;
    private String status;
    private Integer totalChunks;
    private Integer completedChunks;
    private String transcript;
    private List<SubtitleCueRs> cues;
    private List<MeetingChunkRs> chunks;
}
