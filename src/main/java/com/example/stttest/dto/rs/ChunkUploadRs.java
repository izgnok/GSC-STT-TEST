package com.example.stttest.dto.rs;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ChunkUploadRs {
    
    private Long meetingId;
    private Integer chunkSeq;
    private String jobId;
    private String gcsUri;
    
    public ChunkUploadRs(Long meetingId, Integer chunkSeq, String jobId, String gcsUri) {
        this.meetingId = meetingId;
        this.chunkSeq = chunkSeq;
        this.jobId = jobId;
        this.gcsUri = gcsUri;
    }
}