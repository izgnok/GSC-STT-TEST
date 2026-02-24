package com.example.stttest.repository;


import com.example.stttest.entitiy.AiMeetingSttState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiMeetingSttStateRepository extends JpaRepository<AiMeetingSttState, Long> {

    /** 회의ID로 청크 목록 조회 (chunkSeq 순서) */
    List<AiMeetingSttState> findByMeetingIdOrderByChunkSeqAsc(Long meetingId);

    /** 회의의 마지막 청크 조회 (자동 청크번호 계산용) */
    Optional<AiMeetingSttState> findTopByMeetingIdOrderByChunkSeqDesc(Long meetingId);
}
