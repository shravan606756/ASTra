package com.shravan.jcode_intelligence.cli.client;

/**
 * Custom runtime exception wrapping HTTP, serialization, and timeout errors
 * encountered when communicating with the backend.
 */
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public ApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
