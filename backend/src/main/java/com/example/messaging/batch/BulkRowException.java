package com.example.messaging.batch;

/**
 * A single CSV row is unusable. Skippable by the step's skip policy. The {@code reason} is a stable code plus an
 * optional PII-free hint (never row content); it is what ends up in {@code bulk_job_failure.reason}.
 */
public class BulkRowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public BulkRowException(String code, String hint) {
        super(hint == null || hint.isBlank() ? code : code + ": " + hint);
        this.code = code;
    }

    public BulkRowException(String code) {
        this(code, null);
    }

    public String code() {
        return code;
    }
}
