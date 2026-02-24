package com.example.stttest.dto.rs;

import com.example.stttest.entitiy.ChunkStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MeetingChunkRs {
    private Integer chunkSeq;
    private ChunkStatus status;
    private String audioUrl;
    private String transcript;
}
