package com.chardizard.Norbiz.exceptions;

/**
 * Item photo recognition can't run right now: disabled by config, or the embedder sidecar is down
 * or failing. Mapped to 503 SERVICE_UNAVAILABLE with error code {@link #CODE} by
 * GlobalExceptionHandler so the frontend can fall back to text search.
 */
public class ItemRecognitionUnavailableException extends RuntimeException {

    public static final String CODE = "ITEM_RECOGNITION_UNAVAILABLE";

    public ItemRecognitionUnavailableException(String message) {
        super(message);
    }

    public ItemRecognitionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
