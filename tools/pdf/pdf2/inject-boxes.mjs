// One-off, idempotent content tool: inserts "In this project" callouts (real file paths + real code excerpts) into
// docs/interview/**. Re-running is safe: each box carries an <!-- in-project:ID --> marker and is skipped if present.
// Usage: node tools/pdf/pdf2/inject-boxes.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..', '..');
const IV = path.join(root, 'docs', 'interview');
const JP = 'backend/src/main/java/com/example/messaging/';
const F = '`'.repeat(3);

function box(id, file, lang, code, note) {
  const lines = [`<!-- in-project:${id} -->`, '> [!NOTE] In this project', `> **File:** \`${file}\``, '>'];
  if (note) lines.push(`> ${note}`, '>');
  lines.push(`> ${F}${lang}`);
  for (const l of code.replace(/\n+$/, '').split('\n')) lines.push(l.length ? `> ${l}` : '>');
  lines.push(`> ${F}`);
  return lines.join('\n');
}

// target: {file, q:'Q11'} (insert before Follow-ups) or {file, h:'## Heading'} (append at end of that section)
const boxes = [];
const add = (id, target, file, lang, code, note) => boxes.push({ id, target, md: box(id, file, lang, code, note) });

add('b01', { file: '01-java-spring-questions.md', q: 'Q11' }, JP + 'service/IdempotencyService.java', 'java',
`/** Joins the caller's transaction: the record flips to COMPLETED atomically with the business change. */
@Transactional(propagation = Propagation.MANDATORY)
public void complete(String tenantId, String key, String claimId, String requestHash, int status, String body,
        String messageId) {
    Instant expires = clock.instant().plus(props.idempotency().recordTtl());
    repository.complete(claimId, status, body, messageId, expires);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            writeCache(tenantId, key, new Cached(requestHash, status, body, messageId), expires);
        }
    });
}`,
'The annotation is on a public method of a Spring bean called from another bean, so the proxy applies; MANDATORY makes a call without an active transaction fail loudly instead of silently running without one.');

add('b02', { file: '01-java-spring-questions.md', q: 'Q13' }, JP + 'service/IdempotencyService.java', 'java',
`this.requiresNew = new TransactionTemplate(tm);
this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
// ...
private String tryClaim(String tenantId, String key, String hash, Instant now, Instant expires) {
    try {
        return requiresNew.execute(s -> {
            IdempotencyRecord r = new IdempotencyRecord();
            r.setId(Ids.newId());
            r.setTenantId(tenantId);
            r.setIdemKey(key);
            r.setRequestHash(hash);
            r.setStatus(IdempotencyRecord.IN_PROGRESS);
            repository.saveAndFlush(r);
            return r.getId();
        });
    } catch (DataIntegrityViolationException dup) {
        return null;
    }
}`,
'The claim must commit on its own so a concurrent duplicate sees it, and a unique-constraint violation (not a check-then-insert) decides the race. The javadoc says `begin` "must be called outside the business transaction".');

add('b03', { file: '01-java-spring-questions.md', q: 'Q12' }, JP + 'scheduler/OutboxPublisher.java', 'java',
`boolean publishOne(String outboxId) {
    return Boolean.TRUE.equals(tx.execute(status -> {
        if (outbox.markSent(outboxId, clock.instant()) == 0) {
            return false; // another instance won the claim
        }
        OutboxEvent row = outbox.findById(outboxId).orElse(null);
        // ... parse envelope, VALIDATED -> QUEUED via notifications.transition(...)
        bus.publish(envelope); // BusRejectedException rolls the claim back
        return true;
    }));
}`,
'The row claim, the VALIDATED to QUEUED transition and the broker publish share one transaction: a failed publish rolls everything back, which gives at-least-once delivery (DEVIATIONS stage 2, item 3).');

add('b04', { file: '01-java-spring-questions.md', q: 'Q24' }, JP + 'repository/OutboxEventRepository.java', 'java',
`@Modifying(clearAutomatically = true, flushAutomatically = true)
@Transactional
@Query("update OutboxEvent o set o.status = 'SENT', o.sentAt = :now where o.id = :id and o.status = 'NEW'")
int markSent(@Param("id") String id, @Param("now") Instant now);`,
'Three instances may all select the same due rows, but only the one whose UPDATE changes a row (`== 1`) proceeds; the others see `0` and skip. No ShedLock or leader election is needed.');

add('b05', { file: '01-java-spring-questions.md', q: 'Q23' }, JP + 'domain/MessageStateMachine.java', 'java',
`static {
    TABLE.put(ACCEPTED, EnumSet.of(VALIDATED, REJECTED));
    TABLE.put(VALIDATED, EnumSet.of(QUEUED, CANCELLED, REJECTED));
    TABLE.put(QUEUED, EnumSet.of(PROCESSING, CANCELLED));
    TABLE.put(PROCESSING, EnumSet.of(SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED));
    TABLE.put(RETRY_PENDING, EnumSet.of(QUEUED, DEAD_LETTERED, CANCELLED));
    TABLE.put(SENT_TO_PROVIDER, EnumSet.of(DELIVERED, FAILED, DELIVERY_UNKNOWN));
    TABLE.put(DELIVERY_UNKNOWN, EnumSet.of(DELIVERED, FAILED));
    TABLE.put(FAILED, EnumSet.of(DEAD_LETTERED, QUEUED));
    TABLE.put(DEAD_LETTERED, EnumSet.of(QUEUED));
    TABLE.put(DELIVERED, EnumSet.noneOf(MessageStatus.class));
    TABLE.put(REJECTED, EnumSet.noneOf(MessageStatus.class));
    TABLE.put(CANCELLED, EnumSet.noneOf(MessageStatus.class));
}`,
'A pure, table-driven class (an `EnumMap` of `EnumSet`s) with `assertTransition` throwing `IllegalStateTransitionException`. It is an enum table rather than sealed types because the status is persisted as a string column and changed by a compare-and-set UPDATE.');

add('b06', { file: '03-distributed-messaging-questions.md', q: 'Q64' }, JP + 'domain/MessageStateMachine.java', 'java',
`public static ReceiptResolution resolveReceipt(MessageStatus current, DeliveryEventType type) {
    if (type.rank() < 3) {
        return ReceiptResolution.IGNORE_LOWER_RANK;
    }
    if (current == DELIVERED) {
        return type == DeliveryEventType.DELIVERED
                ? ReceiptResolution.IGNORE_LOWER_RANK : ReceiptResolution.CONFLICT;
    }
    boolean receivable = current == PROCESSING || current == SENT_TO_PROVIDER || current == DELIVERY_UNKNOWN;
    // ...
    return type == DeliveryEventType.DELIVERED ? ReceiptResolution.APPLY_DELIVERED
            : ReceiptResolution.APPLY_FAILED;
}`,
'The monotonic rank rule: low-rank receipts (accepted, sent) never move a message backwards, a terminal receipt that overtakes the persisted `SENT_TO_PROVIDER` is applied from `PROCESSING`, and DELIVERED then BOUNCED is a `CONFLICT` that keeps the first outcome.');

add('b07', { file: '05-platform-and-security-questions.md', q: 'Q113' }, JP + 'callback/SignatureVerifier.java', 'java',
`long skew = Math.abs(clock.instant().getEpochSecond() - t);
if (skew > props.callback().tolerance().toSeconds()) {
    return Result.TIMESTAMP_OUT_OF_TOLERANCE;
}
byte[] expected = sign(secret, t, rawBody).getBytes(StandardCharsets.US_ASCII);
boolean match = false;
for (String c : candidates) {
    // bitwise OR keeps the loop free of early exits; isEqual is constant-time for equal lengths
    match |= MessageDigest.isEqual(expected, c.getBytes(StandardCharsets.US_ASCII));
}
return match ? Result.VALID : Result.MISMATCH;`,
'Several `v1=` candidates are accepted so a provider can sign with two secrets during rotation. The MAC is computed over `t + "." + rawBody` on the raw bytes. At the HTTP layer every failure result maps to the same `401 INVALID_SIGNATURE`.');

add('b08', { file: '03-distributed-messaging-questions.md', q: 'Q53' }, JP + 'callback/ReplayGuard.java', 'java',
`public boolean isReplay(String key) {
    Instant cutoff = clock.instant().minus(pipeline.replayWindow());
    Instant cached = cache.get(key);
    if (cached != null && cached.isAfter(cutoff)) {
        return true;
    }
    Optional<ReplayGuardEntry> row = repository.findById(key);
    if (row.isPresent() && row.get().getSeenAt().isAfter(cutoff)) {
        remember(key, row.get().getSeenAt());
        return true;
    }
    return false;
}`,
'A bounded in-memory map in front of the `replay_guard` table (the table stays authoritative). The key is `providerId:eventId`, hashed if it would exceed the 200 character primary key. `markSeen` is called only after successful processing.');

add('b09', { file: '03-distributed-messaging-questions.md', q: 'Q55' }, JP + 'service/RetryPolicy.java', 'java',
`/** Un-jittered backoff for the given (1-based) attempt number; overflow-safe. */
public Duration ceiling(int attemptNo) {
    int shift = Math.min(Math.max(attemptNo - 1, 0), 30);
    long raw = baseMs * (1L << shift);
    if (raw < 0 || raw > capMs) {
        raw = capMs;
    }
    return Duration.ofMillis(raw);
}

/** Jittered backoff in [ceiling/2, ceiling]. */
public Duration backoff(int attemptNo, RandomGenerator random) {
    long ceil = ceiling(attemptNo).toMillis();
    double factor = 0.5 + 0.5 * random.nextDouble();
    return Duration.ofMillis(Math.max(1, (long) (ceil * factor)));
}`,
'Defaults in `application.yml`: `backoff-base-ms: 1000`, `backoff-cap-ms: 60000`, `max-attempts: 5`. This is "equal jitter" (half fixed, half random). Only `ProviderOutcome.isRetryable()` outcomes are retried automatically.');

add('b10', { file: '03-distributed-messaging-questions.md', q: 'Q59' }, JP + 'provider/ResilientProviderInvoker.java', 'java',
`this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
        .failureRateThreshold(cfg.failureRateThreshold())
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(cfg.slidingWindowSize())
        .minimumNumberOfCalls(cfg.minimumCalls())
        .waitDurationInOpenState(cfg.openStateWait())
        .permittedNumberOfCallsInHalfOpenState(cfg.halfOpenCalls())
        // callers consult state() before calling, so the breaker must leave OPEN by itself
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        .build());`,
'One breaker per provider id (`byProvider` map). Provider calls run on a bounded `ThreadPoolExecutor` with an `ArrayBlockingQueue` and `AbortPolicy`, so a hung provider cannot absorb unbounded threads; the state is exported as the gauge `messaging.provider.circuit.state`.');

add('b11', { file: '03-distributed-messaging-questions.md', q: 'Q58' }, JP + 'messaging/BackpressureGuard.java', 'java',
`public void check() {
    long backlog = bus.stats().totalDepth()
            + outbox.countByStatusAndAvailableAtLessThanEqual(OutboxEvent.NEW, clock.instant());
    if (backlog >= limits.maxQueueDepth()) {
        long retryAfter = Math.min(30, 1 + backlog / Math.max(1, props.dispatch().concurrency() * 50L));
        throw new BusRejectedException("Dispatch backlog is full (" + backlog + " waiting); retry later",
                retryAfter);
    }
}`,
'`MessageController.submit` calls this first. The exception handler turns it into `503` with a `Retry-After` header; the default limit is `messaging.backpressure.max-queue-depth: 5000`. Counting due outbox rows too means a stalled publisher is also visible to the guard.');

add('b12', { file: '01-java-spring-questions.md', q: 'Q03' }, JP + 'config/AsyncConfig.java', 'java',
`@Bean(name = "dispatchExecutor")
public ThreadPoolTaskExecutor dispatchExecutor(MessagingProperties props) {
    int n = Math.max(1, props.dispatch().concurrency());
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setThreadNamePrefix("dispatch-");
    ex.setCorePoolSize(n);
    ex.setMaxPoolSize(n);
    ex.setQueueCapacity(Math.max(1, props.dispatch().prefetch()));
    ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    ex.setWaitForTasksToCompleteOnShutdown(true);
    ex.setAwaitTerminationSeconds(30);
    return ex;
}`,
'Fixed pool (core = max = `dispatch.concurrency`, default 8) and a bounded queue equal to the prefetch (default 16). `CallerRunsPolicy` slows the consumer loop when the pool is saturated, which is natural backpressure.');

add('b13', { file: '04-batch-and-data-questions.md', q: 'Q76' }, JP + 'batch/BulkJobConfig.java', 'java',
`@Bean(name = "bulkStepExecutor")
public ThreadPoolTaskExecutor bulkStepExecutor(MessagingProperties props) {
    int n = Math.max(1, props.bulk().concurrency());
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setThreadNamePrefix("bulk-chunk-");
    ex.setCorePoolSize(n);
    ex.setMaxPoolSize(n);
    ex.setQueueCapacity(1000);
    ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    // ...
    return ex;
}`,
'The project uses a multi-threaded step (`concurrency` workers behind a `SynchronizedItemStreamReader`), not partitioning. With more than one worker the reader cannot checkpoint an exact position, so `saveState` is off and a restart re-reads the file; the ledger and the key `bulk-<jobId>-<externalRef>` make processed rows no-ops (DEVIATIONS, batch module).');

add('b14', { file: '04-batch-and-data-questions.md', q: 'Q75' }, JP + 'batch/TokenBucketRateLimiter.java', 'java',
`public void acquire(int permits) {
    if (intervalNanos == 0 || permits <= 0) {
        return;
    }
    long waitNanos;
    synchronized (this) {
        long now = nanoTime.getAsLong();
        if (!started) {
            started = true;
            nextFree = now - burstNanos;
        }
        long earliest = Math.max(nextFree, now - burstNanos);
        nextFree = earliest + intervalNanos * permits;
        waitNanos = Math.max(0, earliest - now);
    }
    if (waitNanos > 0) {
        sleeper.accept(waitNanos);
    }
}`,
'The bulk writer throttles with this limiter (`permitsPerSecond <= 0` means unlimited). The clock and sleeper are injected, so the unit test needs no real sleeping.');

add('b15', { file: '02-rest-and-channels-questions.md', q: 'Q26' }, JP + 'service/IdempotencyService.java', 'java',
`/** Must be called outside the business transaction (it commits the claim on its own). */
public Outcome begin(String tenantId, String key, String requestHash) {
    Optional<Cached> cached = readCache(tenantId, key);
    if (cached.isPresent()) {
        Cached c = cached.get();
        if (!c.hash().equals(requestHash)) {
            throw reused();   // 422 IDEMPOTENCY_KEY_REUSED
        }
        metrics.idempotencyHit();
        return new Outcome(null, new Replay(c.status(), c.body(), c.messageId()));
    }
    // ... tryClaim in REQUIRES_NEW; on conflict load the record:
    //   different hash -> 422, COMPLETED -> replay (200 + Idempotent-Replay: true),
    //   IN_PROGRESS -> take over only if the claim expired, else 409 IDEMPOTENCY_IN_PROGRESS
}`,
'The database record unique on `(tenant_id, idem_key)` is the source of truth; the Redis-style cache is a fast path for completed replays only and is never trusted for correctness.');

add('b16', { file: '03-distributed-messaging-questions.md', q: 'Q50' }, JP + 'messaging/DispatchConsumer.java', 'java',
`Claim result = tx.execute(status -> {
    if (processed.existsByDispatchId(env.dispatchId())) {
        return Claim.DUPLICATE;
    }
    ProcessedMessage pm = new ProcessedMessage();
    pm.setDispatchId(env.dispatchId());
    processed.saveAndFlush(pm);
    boolean won = notifications.transition(m.getId(), MessageStatus.QUEUED, MessageStatus.PROCESSING,
            "claimed by dispatch consumer", ACTOR);
    if (!won) {
        status.setRollbackOnly();
        return Claim.LOST;
    }
    // markClaimed(... lockedUntil ...), insert message_attempt row with state STARTED
    return Claim.WON;
});`,
'ONE transaction: unique `processed_message.dispatch_id` plus compare-and-set QUEUED to PROCESSING plus the attempt row. A concurrent insert of the same `dispatchId` raises `DataIntegrityViolationException`, which is caught and treated as `DUPLICATE`. The provider call happens after this transaction, never inside it.');

add('b17', { file: '05-platform-and-security-questions.md', q: 'Q110' }, JP + 'config/JwtConfig.java', 'java',
`NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(devRsaKey.toRSAPublicKey()).build();
OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
        new JwtTimestampValidator(java.time.Duration.ofSeconds(2)),
        JwtValidators.createDefaultWithIssuer(props.security().devIssuer()));
decoder.setJwtValidator(validator);
return decoder;`,
'This decoder exists only when `messaging.security.dev-token-enabled=true` (profiles local/test; an in-memory RSA key, RS256). Production uses `spring.security.oauth2.resourceserver.jwt.issuer-uri`, where Spring fetches the JWKS and validates signature, `exp`/`nbf` and issuer. The algorithm is fixed by the key type, so `alg: none` and HS256 confusion are not possible.');

add('b18', { file: '05-platform-and-security-questions.md', q: 'Q111' }, JP + 'security/AuthorizationService.java', 'java',
`/**
 * Tenant isolation: a resource of another tenant is reported as 404, never 403, so its existence is not leaked.
 */
public void assertTenantAccess(String ownerTenantId, String what) {
    String scope = tenantContext.scopeTenant();
    if (scope != null && !scope.equals(ownerTenantId)) {
        throw new ApiException.NotFoundException(what);
    }
}`,
'The tenant comes only from the verified JWT claim. `JwtAuthConverter` rejects tokens whose `tenant` claim is missing or invalid (401) and only lets `PLATFORM_ADMIN` carry the wildcard tenant `*`.');

add('b19', { file: '05-platform-and-security-questions.md', q: 'Q108' }, JP + 'config/SecurityConfig.java', 'java',
`http
    .csrf(AbstractHttpConfigurer::disable)
    .formLogin(AbstractHttpConfigurer::disable)
    .httpBasic(AbstractHttpConfigurer::disable)
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(a -> {
        a.requestMatchers("/api/v1/callbacks/**").permitAll();   // HMAC-verified in the service
        a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
        a.requestMatchers("/actuator/**").hasRole(Roles.PLATFORM_ADMIN);
        a.requestMatchers("/api/**", "/dev/**").authenticated();
        a.anyRequest().denyAll();
    })`,
'Default-deny (`anyRequest().denyAll()`), stateless sessions, roles enforced with `@PreAuthorize` on controllers via `@EnableMethodSecurity`. CSRF is disabled only because there is no cookie session: bearer tokens are not sent automatically by browsers.');

add('b20', { file: '01-java-spring-questions.md', q: 'Q18' }, JP + 'web/GlobalExceptionHandler.java', 'java',
`@ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
        org.springframework.transaction.CannotCreateTransactionException.class,
        org.springframework.dao.TransientDataAccessException.class})
public ResponseEntity<ApiError> dependencyUnavailable(Exception e, HttpServletRequest req) {
    log.warn("Database dependency unavailable on {} {}: {}", req.getMethod(), req.getRequestURI(),
            e.getClass().getSimpleName());
    HttpHeaders h = new HttpHeaders();
    h.set(HttpHeaders.RETRY_AFTER, "5");
    return respond(req, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
            "A required dependency is temporarily unavailable; retry with the same idempotency key", null, h);
}`,
'One `@RestControllerAdvice`-style handler class maps every exception to the `ApiError` shape. Note that only the exception class name is logged, never the message, which could contain SQL parameters or customer data.');

add('b21', { file: '01-java-spring-questions.md', q: 'Q15' }, JP + 'service/PreferenceService.java', 'java',
`RecipientPreference p = preferences.findById(recipientId).orElse(null);
if (p == null) {
    if (req.version() != null) {
        throw optimistic();
    }
    // ... create
} else if (req.version() == null || !req.version().equals(p.getVersion())) {
    // stale or missing token: the client must re-read before writing
    throw optimistic();
}`,
'The entity has `@Version private Long version;` and the API requires the client to send it back. A stale version becomes `409 CONFLICT_OPTIMISTIC_LOCK` ("reload and retry"), which the Angular console turns into a conflict dialog.');

add('b22', { file: '04-batch-and-data-questions.md', q: 'Q85' }, JP + 'scheduler/RetentionJob.java', 'java',
`@Scheduled(cron = "\${messaging.retention.cron:0 30 2 * * *}")
public void scheduled() {
    if (!retention.enabled()) {
        return;
    }
    try {
        run();
    } catch (RuntimeException e) {
        log.error("retention run failed: {}", e.getClass().getSimpleName());
    }
}
// run(): purges message_request, delivery_event, audit_event, idempotency_record,
// processed_message, outbox_event in batches; a dry-run mode only counts.`,
'Retention is batched deletes (`batch-size`, `pause-ms`, `max-batches-per-table`) with a dry-run switch, not partition drops. Partitioning by time is the design answer for 50 million rows per month; the reference implementation documents it in `docs/database/retention-and-archival.md`.');

add('b23', { file: '07-leadership-and-fintech-questions.md', q: 'Q154' }, JP + 'scheduler/RetentionJob.java', 'java',
`private static final String NOT_HELD = "not exists (select 1 from legal_hold h where h.released_at is null "
        + "and h.tenant_id = m.tenant_id and (h.scope = 'TENANT' or (h.scope = 'MESSAGE' and h.entity_id = m.id)))";`,
'Every purge query includes this predicate. A hold (scope TENANT or MESSAGE) is active while `released_at IS NULL`; table `legal_hold` comes from migration `V200__bulk_ledger_legal_hold.sql`.');

add('b24', { file: '04-batch-and-data-questions.md', q: 'Q81' }, 'backend/src/main/resources/db/migration/V50__dispatch_indexes.sql', 'sql',
`-- StuckMessageSweeper: PROCESSING with an expired lock
CREATE INDEX idx_msg_status_locked ON message_request (status, locked_until);

-- sweeper (SENT_TO_PROVIDER past the receipt timeout), reconciliation (DELIVERY_UNKNOWN older than reconcile-after),
-- poller (orphaned VALIDATED messages)
CREATE INDEX idx_msg_status_changed ON message_request (status, status_changed_at);

-- reconciliation bookkeeping: count and latest RECONCILIATION_CHECK per message
CREATE INDEX idx_audit_entity_action ON audit_event (entity_type, entity_id, action, occurred_at);`,
'Each index was added for a named query: equality column first (`status`), range column second. `V3__messages.sql` also has `idx_msg_tenant_status_created (tenant_id, status, created_at DESC)` for the list API. No execution plans from a real PostgreSQL were captured in this repository.');

add('b25', { file: '04-batch-and-data-questions.md', q: 'Q93' }, 'backend/src/main/resources/application.yml', 'yaml',
`spring:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
# migrations: V1__tenant_recipient, V2__templates, V3__messages, V4__bulk_audit_idempotency_outbox,
#             V50__dispatch_indexes, V100__spring_batch, V200__bulk_ledger_legal_hold`,
'Flyway owns the schema and Hibernate only validates it (`ddl-auto: validate`). The migrations are plain portable SQL that runs on H2 and PostgreSQL; the Oracle reference DDL lives apart in `db/oracle/oracle_core_schema.sql` and was not extended for V200.');

add('b26', { file: '03-distributed-messaging-questions.md', q: 'Q66' }, JP + 'scheduler/StuckMessageSweeper.java', 'java',
`/**
 * Repairs the two states a crash or a lost receipt can leave behind (ADR-0010), always towards
 * DELIVERY_UNKNOWN and never by blindly retrying:
 *  - PROCESSING with an expired locked_until: the consumer died after claiming (possibly after the
 *    provider accepted). The attempt row stays STARTED so reconciliation can see it.
 *  - SENT_TO_PROVIDER older than messaging.dispatch.receipt-timeout: no delivery receipt arrived.
 * Idempotent and multi-instance safe: each move is a compare-and-set, the loser simply skips.
 */`,
'Class Javadoc, lightly condensed. The lock deadline defaults to `lock-ttl: 2m`. After the sweep, `ReconciliationService` asks the provider what it knows about the idempotency reference.');

add('b27', { file: '05-platform-and-security-questions.md', q: 'Q115' }, JP + 'common/Redaction.java', 'java',
`private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}");
private static final Pattern LONG_DIGITS = Pattern.compile("\\\\+?\\\\d[\\\\d\\\\s-]{8,}\\\\d");
private static final Pattern BEARER = Pattern.compile("(?i)bearer\\\\s+[A-Za-z0-9._~+/=-]+");
public static final String MASK = "***";`,
'Used before raw provider payloads reach Mongo diagnostics. Complementary rule (DEVIATIONS stage 2, item 8): free-text provider reasons are never copied into audit or log lines, only code-like reasons.');

add('b28', { file: '05-platform-and-security-questions.md', q: 'Q116' }, JP + 'template/TemplateRenderer.java', 'java',
`public static String escapeHtml(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
            case '&' -> sb.append("&amp;");
            case '<' -> sb.append("&lt;");
            case '>' -> sb.append("&gt;");
            case '"' -> sb.append("&quot;");
            case '\\'' -> sb.append("&#39;");
            case '\`' -> sb.append("&#96;");
            default -> sb.append(c);
        }
    }
    return sb.toString();
}`,
'For EMAIL the variable values (and defaults) are HTML-escaped; only variables declared by the template are accepted; subject line breaks are stripped to block header injection. Escaping covers HTML text context; URL-valued variables still need allow-listing of schemes.');

add('b29', { file: '06-performance-observability-ai-questions.md', q: 'Q128' }, JP + 'common/CorrelationIdFilter.java', 'java',
`private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
// ...
String incoming = request.getHeader(HEADER);   // X-Correlation-Id
String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : Ids.newId();
request.setAttribute(ATTRIBUTE, id);
MDC.put(MDC_KEY, id);
response.setHeader(HEADER, id);`,
'The inbound id is accepted only if it matches a strict pattern (log-injection defence). The id travels in `DispatchEnvelope.correlationId`, and `DispatchConsumer.handle` puts it back into the MDC with `messageId` and `tenant`, so one grep follows a message across the queue boundary.');

add('b30', { file: '05-platform-and-security-questions.md', q: 'Q101' }, 'infrastructure/docker/Dockerfile.backend', 'dockerfile',
`USER 10001:10001
# Container-aware JVM flags. Override with JAVA_TOOL_OPTIONS / JAVA_OPTS at runtime.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=50 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 ...
ENTRYPOINT ["/usr/bin/tini", "--", "sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]`,
'Multi-stage build (Maven image, then a JRE image), non-root UID 10001, `tini` as PID 1, `exec java` so SIGTERM reaches the JVM for graceful shutdown. The image was NOT built here: Docker was not available.');

add('b31', { file: '05-platform-and-security-questions.md', q: 'Q95' }, 'cloud-foundry/manifest.yml', 'yaml',
`applications:
  - name: messaging-backend-((env))
    buildpacks:
      - java_buildpack
    instances: ((instances))
    memory: ((memory))
    health-check-type: http
    health-check-http-endpoint: /actuator/health/liveness
    env:
      SPRING_PROFILES_ACTIVE: prod-like,rabbit,cloud
      JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
      MESSAGING_SECURITY_DEV_TOKEN_ENABLED: 'false'
      SERVER_SHUTDOWN: graceful
    services: [messaging-postgres, messaging-rabbit, messaging-redis, messaging-mongo]`,
'Deploy command in the file header: `cf push -f cloud-foundry/manifest.yml --vars-file cloud-foundry/vars/dev.yml --strategy rolling`. Secrets are not in the manifest. The manifest is validated by `cloud-foundry/validate-manifest.mjs` only; no CF foundation was available, so nothing was pushed.');

add('b32', { file: '05-platform-and-security-questions.md', q: 'Q104' }, 'Jenkinsfile', 'groovy',
`// stages, in order (declarative pipeline; NOT executed: no Jenkins instance was available)
stage('Version')
stage('Build and test')            { parallel { 'Backend verify'; 'Frontend lint, test, build'; 'Static analysis' } }
stage('Security and integration')  { parallel { 'OWASP dependency-check'; 'npm audit';
                                                'Integration tests (Testcontainers)'; 'Config validation' } }
stage('Docker build and scan')     { parallel { 'Backend image'; 'Frontend image' } }
stage('Deploy to test')            // manual gate: booleanParam DEPLOY_TEST
stage('Post-deploy smoke')`,
'Stage names are taken from the real `Jenkinsfile`; the nesting is summarised. Credentials are referenced by id only. `bamboo-specs/bamboo.yaml` expresses the same flow for Bamboo. Neither pipeline was run.');

add('b33', { file: '05-platform-and-security-questions.md', q: 'Q98' }, 'infrastructure/kubernetes/base/deployment.yaml', 'yaml',
`terminationGracePeriodSeconds: 60   # > app drain timeout (30s) + preStop sleep
# ...
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  periodSeconds: 5
  failureThreshold: 36        # up to 3 minutes to start (Flyway + JPA)
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: http }
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "sleep 10"]`,
'The preStop sleep lets endpoints and ingress stop routing before SIGTERM starts Spring graceful shutdown (`SERVER_SHUTDOWN=graceful`, consumers drained within 30 s). Manifests were statically validated only; no cluster was available.');

add('b34', { file: '05-platform-and-security-questions.md', q: 'Q100' }, 'infrastructure/kubernetes/base/hpa.yaml', 'yaml',
`minReplicas: 2
maxReplicas: 8
behavior:
  scaleDown: { stabilizationWindowSeconds: 300, policies: [{ type: Pods, value: 1, periodSeconds: 60 }] }
  scaleUp:   { stabilizationWindowSeconds: 30,  policies: [{ type: Pods, value: 2, periodSeconds: 60 }] }
metrics:
  - type: Resource
    resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
  - type: External          # needs prometheus-adapter or KEDA
    external:
      metric: { name: messaging_queue_depth }
      target: { type: AverageValue, averageValue: "2000" }`,
'CPU alone is a poor signal for I/O-bound consumers, so the example adds queue depth as an external metric. The file itself warns that the External block must be removed if no external-metrics provider is installed.');

add('b35', { file: '04-batch-and-data-questions.md', q: 'Q90' }, JP + 'diagnostics/ProviderDiagnosticsStore.java', 'java',
`/**
 * Best-effort archive of raw provider callbacks and provider call summaries in MongoDB
 * (provider_diagnostics). It exists for operators ("what exactly did the provider tell us?") and never takes
 * part in the message flow: every write failure is swallowed and counted in
 * messaging.diagnostics.write.failures. Payloads are redacted before they are stored, documents expire
 * through a TTL index, and message content is never stored at all.
 */
public static final String COLLECTION = "provider_diagnostics";`,
'Mongo holds only schema-flexible, expiring, non-critical documents; the message lifecycle stays in the relational database (ADR-0005). Locally an in-memory Mongo-compatible server is used (`MongoConfig`); real MongoDB was not executed.');

add('b36', { file: '03-distributed-messaging-questions.md', q: 'Q69' }, JP + 'coordination/ResilientCoordination.java', 'java',
`/**
 * Decorator: uses the primary (Redis) and falls back to the local in-memory implementation when the primary throws.
 * After a failure the primary is skipped for cooldown so a Redis outage does not add latency to each request.
 * Every fallback increments messaging.redis.fallback. Degraded mode is per-instance (limits are no longer
 * global), which is the accepted trade-off versus failing requests.
 */
public class ResilientCoordination implements CoordinationService {
    private final AtomicLong primaryBlockedUntil = new AtomicLong(0);`,
'The cooldown is the "breaker around Redis" from the answer. Real Redis was not executed; `InMemoryCoordination` is what the local profile and the tests use.');

add('b37', { file: '06-performance-observability-ai-questions.md', q: 'Q123' }, 'performance/k6/backlog-recovery.js', 'javascript',
`// Scenario: pause consumers, build a backlog, resume, measure drain time. Needs PLATFORM_ADMIN (dev token). NOT executed.
export const options = {
  scenarios: { recovery: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '30m' } },
  thresholds: { mp_backlog_drain_seconds: ['p(95)<600'], checks: ['rate>0.99'] },
};`,
'Eight k6 scenarios exist under `performance/k6/` (submit-high-volume, retry-storm, provider-rate-limit, bulk-processing, ...). k6 was not installed, so they were never run and this guide quotes no throughput or latency measurement from them.');

add('b38', { file: '06-performance-observability-ai-questions.md', q: 'Q136' }, 'AGENTS.md', 'markdown',
`## Non-negotiable rules
1. Message status changes only via MessageStateMachine with compare-and-set; no direct status setters.
2. Accepted means durable: persist message + outbox + idempotency + audit in one transaction; publish after commit.
3. Idempotency at API, consumer, callback and provider call.
4. Never hold a DB transaction across a provider call; every remote call has a timeout; pools/queues are bounded.
5. Provider acceptance is not delivery; timeouts/ambiguous outcomes become DELIVERY_UNKNOWN.
6. Tenant comes only from the verified JWT; every tenant-owned query is tenant-scoped; foreign resources return 404.`,
'Repository-level context for coding agents, kept short on purpose; longer guidance sits in `ai-engineering/` (context-engineering.md, spec-driven-workflow.md, multi-agent-workflow.md, skills/, ai-code-review/).');

add('b39', { file: '01-java-spring-questions.md', q: 'Q17' }, 'backend/src/main/resources/application.yml', 'yaml',
`spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
          batch_size: 50
        order_inserts: true
        order_updates: true`,
'Ids are application-generated (`Ids.newId()`), so there is no sequence round trip per insert and JDBC batching can work. The bulk path additionally commits one chunk per transaction.');

add('b40', { file: '04-batch-and-data-questions.md', q: 'Q92' }, 'backend/src/main/resources/application-prod-like.yml', 'yaml',
`spring:
  datasource:
    hikari:
      maximum-pool-size: \${DB_POOL_SIZE:20}
      connection-timeout: 5000`,
'A 5 second connection timeout turns pool exhaustion into a fast error (mapped to `503` + `Retry-After: 5` by the exception handler) instead of threads waiting forever. Budget the pool against instances x pool size versus the database connection limit.');

add('b41', { file: '02-rest-and-channels-questions.md', q: 'Q45' }, JP + 'messaging/DispatchConsumer.java', 'java',
`private Optional<Channel> chooseFallback(MessageRequest m, Recipient recipient) {
    List<Channel> all = m.fallbackList();
    if (recipient == null || m.getFallbackIndex() >= all.size()) {
        return Optional.empty();
    }
    List<Channel> remaining = all.subList(m.getFallbackIndex(), all.size());
    for (PolicyService.FallbackDecision fd : policy.evaluateFallbacks(recipient, remaining, m.getCategory(),
            m.getPriority(), clock.instant())) {
        if (fd.decision().isAllowed()) { // a deferred or rejected fallback is skipped: consent is never bypassed
            return Optional.of(fd.channel());
        }
    }
    return Optional.empty();
}`,
'The request carries at most two fallback channels (`@Size(max = 2)`). The fallback gets a fresh attempt budget and a new idempotency reference group (`messageId:2`, ...).');

add('b42', { file: '03-distributed-messaging-questions.md', q: 'Q57' }, JP + 'messaging/DispatchHandler.java', 'java',
`/**
 * ... (threshold, or the envelope was unreadable). Lets the consumer mirror the dead-lettering in the database.
 * Never called for dead-letters the handler itself requested through BusDecision#deadLetter.
 * Implementations must not throw.
 */
default void onPoison(DispatchEnvelope envelope, int deliveryCount, Throwable cause) {
}
// DlqReason: RETRIES_EXHAUSTED, POISON_MESSAGE, PERMANENT_FAILURE, OPERATOR`,
'`DispatchConsumer.handle` dead-letters an unreadable envelope immediately (`BusDecision.deadLetter(DlqReason.POISON_MESSAGE)`), and the bus dead-letters after a poison threshold, so one bad message cannot block the queue.');

add('b43', { file: '03-distributed-messaging-questions.md', q: 'Q63' }, JP + 'service/ReconciliationService.java', 'java',
`/**
 * Resolves DELIVERY_UNKNOWN messages by asking the provider what it knows about the idempotency reference
 * (messageId:attemptGroup, ADR-0010). ... Lookup results: DELIVERED and FAILED are applied (with a delivery_event
 * recorded as evidence); ACCEPTED leaves the message unknown (the receipt will come); NOT_RECEIVED triggers a safe
 * re-queue as a new attempt with the same idempotency reference (FAILED then QUEUED, both table-legal, in one
 * transaction); a failed or unsupported lookup changes nothing.
 */
@Scheduled(fixedDelayString = "\${messaging.pipeline.scheduler.reconcile-interval-ms:15000}", initialDelay = 7000)`,
'Checks are spaced by `reconcile-after` and bounded by `reconcile-max-checks`; afterwards the message stays visible in `GET /failed-messages` for an operator. Every check is audited as `RECONCILIATION_CHECK`.');

add('b44', { file: '04-batch-and-data-questions.md', q: 'Q74' }, JP + 'batch/BulkJobConfig.java', 'java',
`/*
 *  - chunk size messaging.bulk.chunk-size; one transaction per chunk (messages, outbox, audit, counters)
 *  - fault tolerant: up to messaging.bulk.skip-limit bad rows are skipped and recorded; one more fails the
 *    job; transient database errors are retried retry-limit times with exponential back-off
 *  - JDBC job repository (V100__spring_batch.sql); spring.batch.job.enabled=false: jobs are only
 *    launched through BulkJobService
 */`,
'From the class Javadoc. Counting rule (DEVIATIONS): `processed = succeeded + failed + duplicate`; duplicates are filtered, not skipped, so they do not count against the skip limit; status is `COMPLETED_WITH_ERRORS` iff `failed > 0`.');

// Deep-dive sections
add('d01', { file: 'deep-dive/messaging-and-distributed-systems.md', h: '## Idempotent consumer' }, 'backend/src/main/resources/db/migration/V4__bulk_audit_idempotency_outbox.sql', 'sql',
`CREATE TABLE processed_message (
    id            VARCHAR(36)  NOT NULL,
    dispatch_id   VARCHAR(100) NOT NULL,
    message_id    VARCHAR(36)  NOT NULL,
    processed_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_processed_message PRIMARY KEY (id),
    CONSTRAINT uq_processed_dispatch UNIQUE (dispatch_id)
);
-- V3__messages.sql:  CONSTRAINT uq_msg_idempotency UNIQUE (tenant_id, idempotency_key)
-- V4:                CONSTRAINT uq_idempotency      UNIQUE (tenant_id, idem_key)
-- V3:                CONSTRAINT uq_delivery_event   UNIQUE (provider_id, provider_event_id)`,
'Four unique constraints implement the four idempotency layers: API key, message-level key, consumer dispatch id and provider event id. The database, not application code, decides every race.');

add('d02', { file: 'deep-dive/messaging-and-distributed-systems.md', h: '## Outbox pattern' }, JP + 'scheduler/OutboxPublisher.java', 'java',
`@Scheduled(fixedDelayString = "\${messaging.pipeline.scheduler.outbox-interval-ms:200}", initialDelay = 1000)
public void tick() { ... publishBatch(); ... }

List<OutboxEvent> due = outbox.findByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(OutboxEvent.NEW,
        clock.instant(), PageRequest.of(0, Math.max(1, pipeline.scheduler().outboxBatch())));`,
'`available_at` lets deferred and scheduled messages (quiet hours) wait in the outbox until due. A requeue or replay writes a new outbox row, so the publisher publishes whether the message is VALIDATED or already QUEUED (DEVIATIONS stage 1).');

add('d03', { file: 'deep-dive/security-oauth-owasp.md', h: '## JWT Validation Pitfalls' }, JP + 'security/JwtAuthConverter.java', 'java',
`String tenant = jwt.getClaimAsString("tenant");
boolean valid = tenant != null
        && ((CurrentPrincipal.ANY_TENANT.equals(tenant) && platformAdmin) || TENANT.matcher(tenant).matches());
if (!valid) {
    throw new BadCredentialsException("Token has no valid tenant claim");
}
if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
    throw new BadCredentialsException("Token has no subject");
}`,
'Signature, expiry and issuer are validated by the decoder; this converter adds the application-level claim checks. Unknown roles in the `roles` array grant nothing (they are skipped, not trusted).');

add('d04', { file: 'deep-dive/rest-api-design-and-errors.md', h: '## Idempotency keys' }, JP + 'service/NotificationService.java', 'java',
`private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:~-]{1,128}");
private static final Pattern HEADER_NAME = Pattern.compile("X-[A-Za-z0-9-]{1,50}");
private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(366);

/** @param replay true when an earlier identical request is being replayed (HTTP 200 + Idempotent-Replay). */
public record SubmitResult(MessageAcceptedResponse response, boolean replay) {}`,
'The key format is validated before it is used as a database and cache key. A first submission answers `202 Accepted`; an identical replay answers `200` with `Idempotent-Replay: true`.');

add('d05', { file: 'deep-dive/databases-oracle-mongo.md', h: '## Indexes' }, 'backend/src/main/resources/db/migration/V3__messages.sql', 'sql',
`CREATE INDEX idx_msg_tenant_status_created ON message_request (tenant_id, status, created_at DESC);
CREATE INDEX idx_msg_recipient_created      ON message_request (recipient_id, created_at);
CREATE INDEX idx_msg_status_next_attempt    ON message_request (status, next_attempt_at);
CREATE INDEX idx_msg_status_scheduled       ON message_request (status, scheduled_at);`,
'Equality columns first, then the range or sort column. Because every list query is tenant-scoped, `tenant_id` leads the list index.');

add('d06', { file: 'deep-dive/spring-di-transactions-hibernate.md', h: '## Locking' }, JP + 'domain/MessageRequest.java', 'java',
`@Version
private Long version;
// V3__messages.sql:  version BIGINT NOT NULL DEFAULT 0
// status changes bypass the entity and use compare-and-set UPDATEs, e.g.
//   NotificationService.transition(id, from, to, reason, actor)  -> returns false when the race is lost`,
'Two locking styles side by side: optimistic `@Version` where humans edit (preferences, templates), and compare-and-set UPDATEs where machines race (message status, outbox rows, idempotency takeover).');

add('d07', { file: 'deep-dive/java-jvm-gc-concurrency.md', h: '## Thread pools' }, JP + 'provider/ResilientProviderInvoker.java', 'java',
`int threads = Math.max(1, cfg.executorThreads());
this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(Math.max(1, cfg.executorQueue())), r -> {
            Thread t = new Thread(r, "provider-call-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
this.executor.allowCoreThreadTimeOut(true);`,
'Three bounded pools exist: `dispatch-*` (consumers, CallerRuns), `provider-call-*` (provider calls with time limit, Abort) and `bulk-chunk-*` / `bulk-job-*` (batch, Abort, a full launch queue makes job creation answer 503). Named threads make `jstack` readable.');

add('d08', { file: 'deep-dive/observability-sre-performance.md', h: '## Structured Logs and MDC' }, JP + 'common/CorrelationIdFilter.java', 'java',
`} finally {
    MDC.remove(MDC_KEY);
    MDC.remove("tenant");
    MDC.remove("messageId");
}`,
'MDC is thread-local, so it is cleared in `finally` on every request thread and re-populated by `DispatchConsumer.handle` on consumer threads (`correlationId`, `messageId`, `tenant`).');

function insert(text, b) {
  const t = b.target;
  const marker = `<!-- in-project:${b.id} -->`;
  if (text.includes(marker)) return { text, done: false };
  const lines = text.split('\n');
  let idx;
  if (t.q) {
    const start = lines.findIndex((l) => l.startsWith(`### ${t.q}.`));
    if (start < 0) throw new Error(`question ${t.q} not found in ${t.file}`);
    let end = lines.findIndex((l, i) => i > start && /^#{1,3} /.test(l));
    if (end < 0) end = lines.length;
    idx = lines.findIndex((l, i) => i > start && i < end && l.startsWith('**Follow-ups:**'));
    if (idx < 0) idx = lines.findIndex((l, i) => i > start && i < end && l.startsWith('**Pitfalls:**'));
    if (idx < 0) idx = end;
  } else {
    const start = lines.findIndex((l) => l.trim() === t.h);
    if (start < 0) throw new Error(`heading ${t.h} not found in ${t.file}`);
    let end = lines.findIndex((l, i) => i > start && /^## /.test(l));
    if (end < 0) end = lines.length;
    idx = end;
    while (idx > start + 1 && lines[idx - 1].trim() === '') idx--;
  }
  // keep a blank line before and after the box (box must not be glued to a list item or the next block)
  lines.splice(idx, 0, '', b.md, '');
  return { text: lines.join('\n'), done: true };
}

let n = 0;
const byFile = new Map();
for (const b of boxes) {
  if (!byFile.has(b.target.file)) byFile.set(b.target.file, []);
  byFile.get(b.target.file).push(b);
}
for (const [file, list] of byFile) {
  const p = path.join(IV, file);
  let text = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
  for (const b of list) {
    const r = insert(text, b);
    text = r.text;
    if (r.done) n++;
  }
  fs.writeFileSync(p, text);
}
console.log(`inserted ${n} new boxes (${boxes.length} defined)`);
