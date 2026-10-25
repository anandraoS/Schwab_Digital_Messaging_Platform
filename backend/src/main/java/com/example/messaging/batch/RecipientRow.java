package com.example.messaging.batch;

import java.util.Map;

/**
 * One parsed CSV data row, values still unvalidated (validation is the processor's job so that a bad row can never
 * break {@code FlatFileItemReader} while it fast-forwards to a restart position).
 *
 * <p>{@code rawLine} is kept only so a failure can be recorded as a hash; it is never logged ({@link #toString()} is
 * redacted) and never persisted. {@code parseError} is set (and the other values are null) when the line could not
 * be tokenised, e.g. wrong column count, over-long line or blank line.
 */
public record RecipientRow(long lineNo, String externalRef, String locale, Map<String, String> variables,
        String rawLine, String parseError) {

    public static RecipientRow broken(long lineNo, String rawLine, String parseError) {
        return new RecipientRow(lineNo, null, null, Map.of(), rawLine, parseError);
    }

    @Override
    public String toString() {
        return "RecipientRow[line=" + lineNo + "]";
    }
}
