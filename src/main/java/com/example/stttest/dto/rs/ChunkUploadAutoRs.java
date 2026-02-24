package com.example.stttest.dto.rs;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChunkUploadAutoRs {
    private Long meetingId;
    private Integer chunkSeq;
    private String jobId;
    private String gcsUri;
}
