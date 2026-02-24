package com.example.stttest.dto.stt;

import com.example.stttest.entitiy.ChunkStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class SttJobResultDto {

    private final ChunkStatus status;
    private final String transcript;
    private final List<SttCueDto> cues;
    private final String errorMessage;
}
