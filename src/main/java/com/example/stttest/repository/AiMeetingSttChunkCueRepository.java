package com.example.stttest.repository;

import com.example.stttest.entitiy.AiMeetingSttChunkCue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiMeetingSttChunkCueRepository extends JpaRepository<AiMeetingSttChunkCue, Long> {

    List<AiMeetingSttChunkCue> findByChunkIdOrderByCueIndexAsc(Long chunkId);

    void deleteByChunkId(Long chunkId);
}
