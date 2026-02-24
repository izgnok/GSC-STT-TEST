package com.example.stttest.dto.rs;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MeetingCompleteRs {

    private Long meetingId;
    private String status;  // DONE, WAIT

    public MeetingCompleteRs(Long meetingId, String status) {
        this.meetingId = meetingId;
        this.status = status;
    }

    public static MeetingCompleteRs done(Long meetingId) {
        return new MeetingCompleteRs(meetingId, "DONE");
    }

    public static MeetingCompleteRs wait(Long meetingId) {
        return new MeetingCompleteRs(meetingId, "WAIT");
    }
}