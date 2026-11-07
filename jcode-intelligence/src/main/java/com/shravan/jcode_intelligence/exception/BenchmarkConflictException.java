package com.shravan.jcode_intelligence.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a benchmark execution request is rejected because another benchmark
 * suite is currently executing.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class BenchmarkConflictException extends RuntimeException {

    public BenchmarkConflictException(String message) {
        super(message);
    }

    public BenchmarkConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

