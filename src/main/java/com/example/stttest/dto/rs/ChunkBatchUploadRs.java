package com.example.stttest.dto.rs;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ChunkBatchUploadRs {
    private Long meetingId;
    private Integer uploadedCount;
    private List<ChunkUploadAutoRs> chunks;
}
