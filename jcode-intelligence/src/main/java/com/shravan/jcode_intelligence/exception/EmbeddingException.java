package com.shravan.jcode_intelligence.exception;

public class EmbeddingException extends RuntimeException {

    public EmbeddingException() {
        super();
    }

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
