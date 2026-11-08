package com.shravan.jcode_intelligence.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an indexing request is rejected because another indexing job
 * is concurrently processing the same repository.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class IndexingConflictException extends RuntimeException {

    /**
     * Constructs an {@code IndexingConflictException} with a detail message.
     *
     * @param message failure description
     */
    public IndexingConflictException(String message) {
        super(message);
    }

    /**
     * Constructs an {@code IndexingConflictException} with a detail message and cause.
     *
     * @param message failure description
     * @param cause   underlying exception
     */
    public IndexingConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

