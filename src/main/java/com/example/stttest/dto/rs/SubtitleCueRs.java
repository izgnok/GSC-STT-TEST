package com.example.stttest.dto.rs;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SubtitleCueRs {
    private Integer chunkSeq;
    private Long startMs;
    private Long endMs;
    private String text;
    private String speaker;
}
