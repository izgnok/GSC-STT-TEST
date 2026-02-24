package com.example.stttest.dto.rs;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class MeetingSubtitleRs {
    private Long meetingId;
    private Integer totalChunks;
    private Integer completedChunks;
    private List<SubtitleCueRs> cues;
}
