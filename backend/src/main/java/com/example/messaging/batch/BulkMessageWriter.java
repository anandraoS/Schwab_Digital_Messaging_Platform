package com.example.messaging.batch;

import com.example.messaging.common.Ids;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.service.NotificationService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Submits one chunk through {@link NotificationService#submitInternal}. All rows of a chunk share the chunk
 * transaction, so a chunk is atomic: either every message, outbox row, audit row and counter of the chunk is committed,
 * or none is. The idempotency key {@code bulk-<jobId>-<externalRef>} makes a replay after a crash or restart a no-op.
 *
 * <p>Idempotency claims are committed in their own transaction by {@code IdempotencyService.begin}; when the chunk
 * rolls back, those claims would stay IN_PROGRESS and block the retry, so they are released after the rollback.
 */
public class BulkMessageWriter implements ItemWriter<BulkMessageItem> {

    private final BulkJobContext ctx;
    private final NotificationService notifications;
    private final BulkJobStore store;
    private final TokenBucketRateLimiter limiter;
    private final BulkWriterStats stats;

    public BulkMessageWriter(BulkJobContext ctx, NotificationService notifications, BulkJobStore store,
            TokenBucketRateLimiter limiter, BulkWriterStats stats) {
        this.ctx = ctx;
        this.notifications = notifications;
        this.store = store;
        this.limiter = limiter;
        this.stats = stats;
    }

    @Override
    public void write(Chunk<? extends BulkMessageItem> chunk) {
        stats.enter();
        try {
            List<String> keys = new ArrayList<>(chunk.size());
            registerRollbackCleanup(keys);
            long accepted = 0;
            for (BulkMessageItem item : chunk) {
                limiter.acquire();
                keys.add(item.idempotencyKey());
                NotificationService.SubmitResult result = notifications.submitInternal(ctx.tenantId(), item.request(),
                        item.idempotencyKey(), ctx.jobId(), ctx.createdBy());
                if (result.replay()) {
                    continue; // committed by an earlier run: already counted there
                }
                if (result.response().status() == MessageStatus.REJECTED) {
                    // persisted (audit trail) but never sent: policy said no (opt-out, suppression, ...)
                    store.recordFailure(ctx.jobId(), item.lineNo(), BulkJobStore.Outcome.FAILED,
                            "POLICY_REJECTED: " + result.response().statusReason(),
                            Ids.sha256Hex(item.row().rawLine()));
                } else {
                    accepted++;
                }
            }
            store.addSucceeded(ctx.jobId(), accepted);
        } finally {
            stats.exit();
        }
    }

    private void registerRollbackCleanup(List<String> keys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED && !keys.isEmpty()) {
                    store.releaseIdempotencyClaims(ctx.tenantId(), keys);
                }
            }
        });
    }
}
