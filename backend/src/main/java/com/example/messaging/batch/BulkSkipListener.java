package com.example.messaging.batch;

import com.example.messaging.common.ApiException;
import com.example.messaging.common.Ids;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.item.file.FlatFileParseException;

/**
 * Turns skipped items into {@code bulk_job_failure} rows. Spring Batch invokes these callbacks inside the chunk
 * transaction just before commit (so a retried chunk reports a skip only once); recording is also idempotent per
 * {@code (job, line)}. The stored reason is a stable code with a PII-free hint and the row is represented by the
 * SHA-256 of its raw line - row content is never stored.
 */
public class BulkSkipListener implements SkipListener<RecipientRow, BulkMessageItem> {

    private final String jobId;
    private final BulkJobStore store;

    public BulkSkipListener(String jobId, BulkJobStore store) {
        this.jobId = jobId;
        this.store = store;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof FlatFileParseException e) {
            store.recordFailure(jobId, e.getLineNumber(), BulkJobStore.Outcome.FAILED, "MALFORMED_ROW",
                    hash(e.getInput()));
        }
    }

    @Override
    public void onSkipInProcess(RecipientRow item, Throwable t) {
        store.recordFailure(jobId, item.lineNo(), BulkJobStore.Outcome.FAILED, reason(t), hash(item.rawLine()));
    }

    @Override
    public void onSkipInWrite(BulkMessageItem item, Throwable t) {
        store.recordFailure(jobId, item.lineNo(), BulkJobStore.Outcome.FAILED, reason(t), hash(item.row().rawLine()));
    }

    private static String hash(String raw) {
        return raw == null ? null : Ids.sha256Hex(raw);
    }

    /** Never includes exception messages that could echo input; ApiException messages are fixed, PII-free texts. */
    static String reason(Throwable t) {
        if (t instanceof BulkRowException b) {
            return b.getMessage();
        }
        if (t instanceof ApiException a) {
            return a.getCode().name() + ": " + a.getMessage();
        }
        return "ROW_FAILED: " + t.getClass().getSimpleName();
    }
}
