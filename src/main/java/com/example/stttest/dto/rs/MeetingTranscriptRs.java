package com.example.stttest.dto.rs;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MeetingTranscriptRs {

    private Long meetingId;
    private String transcript;
    private Integer totalChunks;
    private Integer completedChunks;

    public MeetingTranscriptRs(Long meetingId, String transcript,
                               Integer totalChunks, Integer completedChunks) {
        this.meetingId = meetingId;
        this.transcript = transcript;
        this.totalChunks = totalChunks;
        this.completedChunks = completedChunks;
    }
}