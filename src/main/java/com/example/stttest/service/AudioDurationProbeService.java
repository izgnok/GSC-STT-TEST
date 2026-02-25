package com.example.stttest.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
public class AudioDurationProbeService {

    /**
     * 업로드된 webm-opus 파일의 실제 재생 길이를 ffprobe로 측정한다.
     *
     * 클라이언트가 보내는 duration 값 대신 서버가 동일 기준으로 계산한 duration을 사용해야
     * 청크 누적 오프셋이 병합 오디오 타임라인과 최대한 일치한다.
     */
    public long probeWebmDurationMs(MultipartFile audioFile) throws Exception {
        Path tempFile = Files.createTempFile("stt-chunk-", ".webm");
        try {
            audioFile.transferTo(tempFile);

            List<String> command = List.of(
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=nokey=1:noprint_wrappers=1",
                tempFile.toAbsolutePath().toString()
            );

            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("ffprobe failed. exitCode=%d, output=%s".formatted(exitCode, output));
            }

            double seconds = Double.parseDouble(output);
            long durationMs = Math.round(seconds * 1000d);
            if (durationMs <= 0L) {
                throw new IllegalStateException("invalid probed duration. output=" + output);
            }
            return durationMs;
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception e) {
                log.warn("temp file cleanup failed: {}", tempFile, e);
            }
        }
    }
}

