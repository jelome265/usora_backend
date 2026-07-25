package com.usora.identity.exception;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ErrorResponse {
    private String code;
    private String message;
    private String target;
    private List<Detail> details;
    private String requestId;
    private Instant timestamp;
    private String documentationUrl;
    private String path;

    @Data
    @Builder
    public static class Detail {
        private String code;
        private String message;
        private String target;
    }
}
