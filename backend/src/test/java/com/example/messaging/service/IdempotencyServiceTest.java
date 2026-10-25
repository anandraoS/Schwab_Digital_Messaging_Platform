package com.example.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.domain.IdempotencyRecord;
import com.example.messaging.repository.IdempotencyRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class IdempotencyServiceTest {

    private static final String TENANT = "tenant-retail";

    @Autowired
    IdempotencyService service;
    @Autowired
    IdempotencyRecordRepository repository;
    @Autowired
    PlatformTransactionManager tm;

    private static String key() {
        return "idem-" + UUID.randomUUID();
    }

    private void complete(IdempotencyService.Outcome o, String key, String hash, String body) {
        new TransactionTemplate(tm).executeWithoutResult(s ->
                service.complete(TENANT, key, o.claimId(), hash, 202, body, "msg-1"));
    }

    @Test
    void firstUseClaimsTheKeyAndCompletionMakesLaterCallsReplay() {
        String k = key();
        IdempotencyService.Outcome first = service.begin(TENANT, k, "h1");
        assertThat(first.isReplay()).isFalse();
        assertThat(first.claimId()).isNotBlank();
        assertThat(repository.findByTenantIdAndIdemKey(TENANT, k)).get()
                .extracting(IdempotencyRecord::getStatus).isEqualTo(IdempotencyRecord.IN_PROGRESS);

        complete(first, k, "h1", "{\"id\":\"msg-1\"}");

        IdempotencyService.Outcome second = service.begin(TENANT, k, "h1");
        assertThat(second.isReplay()).isTrue();
        assertThat(second.replay().status()).isEqualTo(202);
        assertThat(second.replay().body()).isEqualTo("{\"id\":\"msg-1\"}");
        assertThat(second.replay().messageId()).isEqualTo("msg-1");
    }

    @Test
    void sameKeyWithDifferentHashIsRejectedBothWhileInProgressAndAfterCompletion() {
        String k = key();
        IdempotencyService.Outcome first = service.begin(TENANT, k, "h1");
        assertThatThrownBy(() -> service.begin(TENANT, k, "OTHER"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        complete(first, k, "h1", "{}");
        assertThatThrownBy(() -> service.begin(TENANT, k, "OTHER"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        // and the cache fast path enforces the same rule
        assertThatThrownBy(() -> service.begin(TENANT, k, "STILL-OTHER")).isInstanceOf(ApiException.class);
    }

    @Test
    void inFlightDuplicateIsReportedAsInProgress() {
        String k = key();
        service.begin(TENANT, k, "h1");
        assertThatThrownBy(() -> service.begin(TENANT, k, "h1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
    }

    @Test
    void keysAreScopedPerTenant() {
        String k = key();
        assertThat(service.begin(TENANT, k, "h1").isReplay()).isFalse();
        assertThat(service.begin("tenant-wealth", k, "h-different").isReplay()).isFalse();
    }

    @Test
    void releasedClaimCanBeTakenAgain() {
        String k = key();
        IdempotencyService.Outcome first = service.begin(TENANT, k, "h1");
        service.release(first.claimId());
        assertThat(repository.findByTenantIdAndIdemKey(TENANT, k)).isEmpty();
        assertThat(service.begin(TENANT, k, "h2").isReplay()).as("a failed attempt does not burn the key").isFalse();
    }

    @Test
    void abandonedClaimIsTakenOverOnlyAfterItsLockExpired() {
        String k = key();
        IdempotencyService.Outcome first = service.begin(TENANT, k, "h1");
        IdempotencyRecord rec = repository.findByTenantIdAndIdemKey(TENANT, k).orElseThrow();
        // simulate a crashed worker: the claim's lock TTL has elapsed
        new TransactionTemplate(tm).executeWithoutResult(s -> {
            IdempotencyRecord r = repository.findById(rec.getId()).orElseThrow();
            r.setExpiresAt(Instant.now().minusSeconds(5));
            repository.save(r);
        });
        IdempotencyService.Outcome takeover = service.begin(TENANT, k, "h1");
        assertThat(takeover.isReplay()).isFalse();
        assertThat(takeover.claimId()).isEqualTo(first.claimId());
    }

    @Test
    void concurrentIdenticalRequestsAdmitExactlyOneProcessor() throws Exception {
        String k = key();
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<String> task = () -> {
                start.await();
                try {
                    IdempotencyService.Outcome o = service.begin(TENANT, k, "same");
                    return o.isReplay() ? "REPLAY" : "PROCEED";
                } catch (ApiException e) {
                    return e.getCode().name();
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();
        assertThat(outcomes.stream().filter("PROCEED"::equals).count()).as(outcomes.toString()).isEqualTo(1);
        assertThat(outcomes).allMatch(o -> o.equals("PROCEED") || o.equals("IDEMPOTENCY_IN_PROGRESS") || o.equals("REPLAY"));
    }
}
