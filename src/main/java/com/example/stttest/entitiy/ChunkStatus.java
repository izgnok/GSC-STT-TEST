package com.example.stttest.entitiy;

/**
 * STT 청크 처리 상태
 */
public enum ChunkStatus {
    PROCESSING,  // STT 진행 중
    DONE,        // STT 완료
    ERROR        // STT 실패
}