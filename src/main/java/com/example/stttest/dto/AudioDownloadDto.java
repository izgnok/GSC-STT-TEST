package com.example.stttest.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AudioDownloadDto {
    private final String fileName;
    private final String contentType;
    private final byte[] bytes;
}