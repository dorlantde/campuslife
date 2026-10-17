package com.campuslife.common.exception;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/** Expected business failure; message must be safe for client display. */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(Objects.requireNonNull(message));
        this.status = Objects.requireNonNull(status);
        this.code = Objects.requireNonNull(code);
        if (!status.isError()) {
            throw new IllegalArgumentException("Business failures require an error HTTP status");
        }
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
