package com.example.stttest.entitiy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "ai_meeting_stt_chunk_cue",
    indexes = {
        @Index(name = "idx_chunk_cue_meeting_chunk", columnList = "meeting_id,chunk_id,chunk_seq,cue_index"),
        @Index(name = "idx_chunk_cue_chunk", columnList = "chunk_id,cue_index")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiMeetingSttChunkCue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    @Column(name = "chunk_id", nullable = false)
    private Long chunkId;

    @Column(name = "chunk_seq", nullable = false)
    private Integer chunkSeq;

    @Column(name = "cue_index", nullable = false)
    private Integer cueIndex;

    @Column(nullable = false)
    private Long startMs;

    @Column(nullable = false)
    private Long endMs;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    private String speaker;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
