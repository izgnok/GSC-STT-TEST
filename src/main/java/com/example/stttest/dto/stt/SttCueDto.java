package com.example.stttest.dto.stt;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SttCueDto {

    private final long startMs;
    private final long endMs;
    private final String text;
    private final String speaker;
}
