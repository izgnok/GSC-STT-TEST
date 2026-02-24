package com.example.stttest.entitiy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_meeting_stt_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiMeetingSttState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 회의 ID */
    @Column(nullable = false)
    private Long meetingId;

    /** 청크 순번 */
    @Column(nullable = false)
    private Integer chunkSeq;

    /** GCS URI (음성파일) */
    @Column(nullable = false)
    private String gcsUri;

    /** STT Job ID (operation name) */
    @Column(nullable = false)
    private String jobId;

    /** 업로드된 청크의 원본 길이(ms). 자막 합치기 오프셋 보정에 사용 */
    private Long durationMs;

    /** 처리 상태: PROCESSING, DONE, ERROR */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ChunkStatus status;

    /** STT 결과 텍스트 */
    @Column(columnDefinition = "TEXT")
    private String transcript;

    /** 에러 메시지 */
    private String errorMessage;

    /** 주 언어 코드 */
    private String languageCode;

    /** 생성일 (폴더 구조용) */
    @Column(nullable = false)
    private LocalDate createdDate;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        createdDate = LocalDate.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
