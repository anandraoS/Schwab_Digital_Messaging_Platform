package com.example.messaging.batch;

import com.example.messaging.web.dto.MessageDtos.SubmitMessageRequest;

/** A validated row, ready to be submitted. {@code toString} is redacted: the request carries template variables. */
public record BulkMessageItem(RecipientRow row, SubmitMessageRequest request, String idempotencyKey) {

    public long lineNo() {
        return row.lineNo();
    }

    @Override
    public String toString() {
        return "BulkMessageItem[line=" + row.lineNo() + "]";
    }
}
