package com.example.stttest.dto.rs;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class MeetingChunksRs {
    private Long meetingId;
    private Integer totalChunks;
    private Integer completedChunks;
    private List<MeetingChunkRs> chunks;
}
