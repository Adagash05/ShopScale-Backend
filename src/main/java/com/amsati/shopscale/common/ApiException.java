package com.amsati.shopscale.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final java.util.Map<String, Object> details;

    public ApiException(HttpStatus status, String message) { this(status, message, null, null); }

    public ApiException(HttpStatus status, String message, String code, java.util.Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() { return status; }
    /** Machine-readable reason (e.g. PLAN_LIMIT_REACHED, FEATURE_NOT_IN_PLAN) that the frontend turns into an upgrade prompt. */
    public String getCode() { return code; }
    public java.util.Map<String, Object> getDetails() { return details; }

    /** 402 with a code the frontend can act on: the plan does not allow this. */
    public static ApiException planLimit(String code, String message, java.util.Map<String, Object> details) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, message, code, details);
    }

    public static ApiException badRequest(String m) { return new ApiException(HttpStatus.BAD_REQUEST, m); }
    public static ApiException unauthorized(String m) { return new ApiException(HttpStatus.UNAUTHORIZED, m); }
    public static ApiException paymentRequired(String m) { return new ApiException(HttpStatus.PAYMENT_REQUIRED, m); }
    public static ApiException forbidden(String m) { return new ApiException(HttpStatus.FORBIDDEN, m); }
    public static ApiException notFound(String m) { return new ApiException(HttpStatus.NOT_FOUND, m); }
    public static ApiException conflict(String m) { return new ApiException(HttpStatus.CONFLICT, m); }
}
