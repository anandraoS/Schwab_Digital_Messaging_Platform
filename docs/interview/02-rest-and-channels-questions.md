# Question Bank 2: High-Volume REST APIs and Email / SMS / Push Channels (Q25-Q46)

Reference-implementation study material. Claims about the platform cite CONTRACT section X; channel facts are stated generally and are not tied to any specific vendor or employer.

## REST API Design

### Q25. How would you design the submission endpoint of a high-volume messaging API so it stays fast when providers are slow?
**Tags:** REST API, Senior, Scenario
**Analogy:** A post office counter that stamps and receipts your parcel immediately; the trucks leave later, so a slow truck never makes the counter line longer.
**Model answer:**
Clarify requirements: peak submissions per second, latency SLO for the API (for example p99 under 200 ms), durability expectation (an accepted message must not be lost), and ordering needs. Then decouple acceptance from delivery.

Approach (matches `POST /api/v1/messages`, CONTRACT section 5):
- Validate synchronously: schema, tenant, recipient existence, template variables, `Idempotency-Key` required.
- Persist `message_request` (VALIDATED), `outbox_event` and audit in one local transaction, then return `202 Accepted` with `Location` and the message id (CONTRACT section 6). No provider call on the request thread.
- A publisher moves outbox rows to the bus; dispatch consumers call providers asynchronously.
- Clients poll `GET /messages/{id}/status` (lightweight) or receive callbacks/webhooks.

Failure modes and protections:
- Database slowness: keep the transaction tiny, index the idempotency key (`UNIQUE(tenant_id, idempotency_key)`), pool size sized to concurrency.
- Queue depth growth: backpressure threshold returns `503` with `Retry-After` when `messaging.backpressure.max-queue-depth` is exceeded.
- Abuse: per-client rate limit, request size limit (256 KB, 413).
- Duplicates from client retries: idempotency replay.

What I would measure: accept latency percentiles, 202 rate versus 4xx/5xx, outbox lag, queue depth and age, accepted-to-provider-submit delay (`messaging.dispatch.delay`).

Trade-off: asynchronous acceptance means the caller learns delivery outcome later; you must give them a status API and a clear lifecycle where acceptance is not delivery.
**Follow-ups:**
- Why 202 and not 201?
- What do you return if the template does not exist?
- How do you handle scheduled sends?
**Pitfalls:**
- Calling the provider synchronously inside the request.
- Returning 200 with "sent" before anything was sent.
- No backpressure strategy.

### Q26. Explain how an Idempotency-Key header should work end to end, including edge cases.
**Tags:** REST API, Senior, Concept
**Analogy:** A ticket number at the deli: if you shout your number twice, you still get only one sandwich.
**Model answer:**
Purpose: let clients retry unsafe requests (POST) after timeouts without creating duplicates. Contract from CONTRACT section 3.3 item 6 and section 4 (`idempotency_record`):

- Key scope: `(tenant, Idempotency-Key)`; the same key from different tenants is unrelated.
- First request: atomically claim the key (insert row `IN_PROGRESS` with `request_hash`, relying on `UNIQUE(tenant_id, idem_key)` to arbitrate races), process, then store `COMPLETED` with response status/body and message id, with an expiry (for example 24 hours).
- Same key, same request hash, completed: return the original response with `Idempotent-Replay: true` and do not reprocess.
- Same key, different hash: HTTP 422 `IDEMPOTENCY_KEY_REUSED`; it indicates a client bug.
- Same key while the first is still running: HTTP 409 `IDEMPOTENCY_IN_PROGRESS` with `Retry-After`.
- Hash: canonical serialization of the body (sorted keys, normalized whitespace) plus method and path.

Edge cases:
- Crash after claim but before completion: a stale `IN_PROGRESS` needs a lease timeout, otherwise the key is stuck. Better, perform the claim and the business write in the same transaction so a crash rolls both back (the contract's submission transaction does this).
- Redis fast-path: the platform may check Redis first, but the database remains the source of truth, so a Redis outage only costs latency (`ResilientCoordination`, CONTRACT section 1).
- Failures: do not cache 5xx responses as completed; allow retry.
- Key generation: client-generated UUID per logical operation, reused on retry.

Metric: `messaging.idempotency.hits`.

<!-- in-project:b15 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/service/IdempotencyService.java`
>
> The database record unique on `(tenant_id, idem_key)` is the source of truth; the Redis-style cache is a fast path for completed replays only and is never trusted for correctness.
>
> ```java
> /** Must be called outside the business transaction (it commits the claim on its own). */
> public Outcome begin(String tenantId, String key, String requestHash) {
>     Optional<Cached> cached = readCache(tenantId, key);
>     if (cached.isPresent()) {
>         Cached c = cached.get();
>         if (!c.hash().equals(requestHash)) {
>             throw reused();   // 422 IDEMPOTENCY_KEY_REUSED
>         }
>         metrics.idempotencyHit();
>         return new Outcome(null, new Replay(c.status(), c.body(), c.messageId()));
>     }
>     // ... tryClaim in REQUIRES_NEW; on conflict load the record:
>     //   different hash -> 422, COMPLETED -> replay (200 + Idempotent-Replay: true),
>     //   IN_PROGRESS -> take over only if the claim expired, else 409 IDEMPOTENCY_IN_PROGRESS
> }
> ```

**Follow-ups:**
- Why hash the request and not just the key?
- How long should keys be retained and why?
- How does this differ from consumer-side deduplication?
**Pitfalls:**
- Check-then-insert without a unique constraint.
- Caching error responses permanently.
- Using a per-request random key (defeats the purpose).

### Q27. Offset pagination versus keyset pagination: which would you use for the message list API and why?
**Tags:** REST API, Intermediate, Concept
**Analogy:** Offset is "skip the first 10,000 pages and read the next 20"; keyset is "continue from the bookmark you left".
**Model answer:**
Offset pagination (`LIMIT 50 OFFSET 100000`) is simple and supports jumping to page N and showing total pages, but the database must scan and discard all skipped rows, so deep pages get slower, and rows inserted between requests cause duplicates or gaps.

Keyset (seek) pagination filters on the last seen sort key: `WHERE (created_at, id) < (:lastCreatedAt, :lastId) ORDER BY created_at DESC, id DESC LIMIT 50`. It uses the index directly (the contract index `(tenant_id, status, created_at DESC)`, CONTRACT section 4) and is stable under concurrent inserts. Downsides: no random page access, no cheap total count, a composite sort key is needed for uniqueness, and the cursor must be opaque and signed or encoded.

Recommendation: the contract exposes `page`, `size` and `PageResponse` with `totalElements` (CONTRACT section 5), which fits operator consoles with filters and bounded result sets, with `size <= 200`. For large exports and machine consumers (event streams, `delivery-events` over days), I would add keyset/cursor pagination or a time-window query. Always guard against deep offsets with a maximum page depth or require a date range.

Practical tips:
- `COUNT(*)` on large filtered tables is costly: make it optional, approximate, or skip.
- Always include a deterministic tie-breaker (`id`) in the sort.
- Ensure the sort columns are indexed together with the filters.
- Oracle 12c+ uses `OFFSET ... FETCH NEXT`; older ROWNUM tricks are error-prone.
**Follow-ups:**
- How would you encode a cursor?
- What if the user sorts by a non-indexed column?
- Why is `totalElements` expensive?
**Pitfalls:**
- Offset pagination over millions of rows.
- Sorting without a tie-breaker.
- Allowing arbitrary sort columns without indexes.

### Q28. How do you implement API rate limiting for multiple enterprise clients, and what should the 429 response contain?
**Tags:** REST API, Intermediate, Concept
**Analogy:** A turnstile that refills a limited number of tokens every second; if you arrive when the basket is empty, you wait.
**Model answer:**
Common algorithms:
- Token bucket: a bucket of capacity `burst` refills at `rate` per second; each request takes a token. Allows bursts, smooth average. The platform's defaults are 50 req/s burst 100 per client, configurable (CONTRACT section 5).
- Leaky bucket: smooths output to a constant rate.
- Fixed window counter: simple but allows a double burst at window edges.
- Sliding window log/counter: accurate, more memory.

Implementation across instances: state must be shared. Use Redis with an atomic Lua script that computes refill and consumption in one call (the contract's Lettuce Lua token bucket), keyed by `(clientId)` or `(tenant, clientApp)`. Fall back to a local in-memory limiter if Redis is down so the API stays available (`ResilientCoordination`, counter `messaging.redis.fallback`), accepting that limits become per instance during the outage.

Response: `429 Too Many Requests` with `Retry-After` (seconds), `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, and the standard `ApiError` body with code `RATE_LIMITED` (CONTRACT section 5). Metric `messaging.ratelimit.rejected{scope}`.

Design choices:
- Limit by authenticated client, not IP (enterprise clients sit behind NAT).
- Separate limits per endpoint class (submission versus reads) and a stricter limit for bulk.
- Prefer rejecting early in a filter (`RateLimitFilter`) before expensive work.
- Clients should use exponential backoff with jitter and honor `Retry-After`.

Rate limiting protects the platform; a separate provider rate limiter protects downstreams.
**Follow-ups:**
- Why a Lua script rather than GET then SET?
- What happens to limits during a Redis outage?
- How do you give premium clients higher limits?
**Pitfalls:**
- Limiting by IP only.
- No `Retry-After`.
- Check-then-set races in the limiter.

### Q29. A client's gateway times out after 30 seconds and retries POST /messages, and customers receive two emails. Find the gap and fix it.
**Tags:** REST API, Senior, Scenario
**Analogy:** Pressing the elevator button again because the light did not turn on; if the first press registered, you have not made the elevator come twice, but a naive system would.
**Model answer:**
Clarify: Which timeout fired (client to gateway, gateway to service)? Did the first request complete server-side? Was an `Idempotency-Key` sent and did it stay stable on retry? A retry with a new key is a new message; a client or gateway generating a fresh key per attempt defeats idempotency.

Diagnosis:
- Search by `correlationId` and `clientReference` for two `message_request` rows with different idempotency keys.
- Look at accept latency; a 30-second response means something on the request path is blocking (provider call, lock wait, slow DB).

Fixes:
- Server: make `Idempotency-Key` mandatory (CONTRACT section 3.3), so a request lacking it is rejected with 400; deduplicate on `(tenant, key)` with a unique constraint; return `202` quickly (no provider call on the request thread).
- Client contract: generate one key per logical send and reuse it on retries; document retry guidance (retry on 5xx/timeouts with the same key, with backoff; do not retry 4xx except 409 in-progress/429).
- Business-level dedupe as a second line: optional `clientReference` uniqueness per tenant (for example `order-8841-confirm`) to catch clients that use fresh keys.
- Gateway: do not auto-retry non-idempotent methods unless the key header is passed through.

Failure modes: key reuse with a changed body returns 422 so bugs surface. Concurrent first requests resolve through the unique constraint, loser gets 409 `IDEMPOTENCY_IN_PROGRESS`.

What I would measure: the idempotent replay rate (`messaging.idempotency.hits`), count of duplicate `clientReference` values, p99 accept latency.
**Follow-ups:**
- Should replay return the same status code?
- How would you detect clients that never send stable keys?
- What if the provider also times out?
**Pitfalls:**
- Blaming the client without server-side dedupe.
- Making the key optional.
- Keys reused across different payloads.

### Q30. How do you evolve an API without breaking the enterprise applications that depend on it?
**Tags:** REST API, Intermediate, Concept
**Analogy:** Adding a new door to a building without bricking up the old one while tenants still use it.
**Model answer:**
Principles: be conservative in what you send and liberal in what you accept; make additive changes within a version; introduce a new version only for breaking changes.

Additive (non-breaking): new optional request fields, new response fields (clients must ignore unknown fields), new endpoints, new enum values only if clients are told to tolerate them (document it, because enum additions break strict deserializers).

Breaking: removing or renaming fields, changing types or semantics, making an optional field required, tightening validation, changing error codes.

Versioning options:
- URI versioning (`/api/v1/...`, as the platform does, CONTRACT section 5): explicit, cacheable, easy to route. The `API-Version: 1` response header reinforces it.
- Header or media-type versioning: cleaner URLs, harder to test and cache.

Process:
- A committed OpenAPI spec (`messaging-api-v1.yaml`) with a test that the running app matches it (CONTRACT section 1), plus a diff tool in CI (such as openapi-diff) that fails on breaking changes.
- Consumer-driven contract tests for major clients.
- Deprecation policy: `Deprecation` and `Sunset` headers, documentation, usage metrics per client app to see who still calls the old version, a stated support window, and direct outreach to owners.
- Feature flags and tolerant readers for rollout; expand-and-contract for schema changes behind the API.

Measure: calls by version and client, deprecation burn-down.
**Follow-ups:**
- Is adding an enum value a breaking change?
- How do you version webhook payloads?
- How do you handle a database migration behind a stable API?
**Pitfalls:**
- Silent semantic changes under the same version.
- No usage telemetry before removal.
- Versioning every minor tweak.

### Q31. Which HTTP status codes does a messaging API use and why? Explain 202, 409, 422, 429 and 503 with examples.
**Tags:** REST API, Foundational, Concept
**Analogy:** Traffic signals: each color tells the driver exactly what to do next, so everyone reacts the same way.
**Model answer:**
- `202 Accepted`: request validated and durably recorded; processing continues asynchronously. Used by `POST /messages` and `POST /bulk-jobs` with `Location` pointing to the resource (CONTRACT section 5).
- `200 OK`: successful reads, cancel, and idempotent replay (`Idempotent-Replay: true`).
- `400 Bad Request`: malformed JSON or validation failure (`MALFORMED_REQUEST`, `VALIDATION_FAILED`).
- `401 Unauthorized`: missing or invalid token. `403 Forbidden`: authenticated but role lacks permission. Note: another tenant's resource returns `404` to avoid leaking existence.
- `404 Not Found`.
- `409 Conflict`: state conflicts: cancel on a delivered message (`ILLEGAL_STATE_TRANSITION`), optimistic-lock conflict, in-flight duplicate idempotency key.
- `413 Payload Too Large`: body over 256 KB.
- `422 Unprocessable Content`: syntactically valid but semantically rejected, for example idempotency key reused with a different body.
- `429 Too Many Requests` with `Retry-After`: client rate limit.
- `500` unexpected server fault; `503 Service Unavailable` with `Retry-After`: backpressure or dependency down.

Guidance:
- Use 4xx when the client can fix the request and 5xx when retrying later might succeed; this drives client retry behavior.
- Distinguish a policy suppression: in this platform a policy rejection at submission (opt-out, suppression, deleted recipient, no address) is persisted as a `REJECTED` message and answered `202` with `status=REJECTED` and `statusReason`, not a 4xx, so the idempotent replay and the audit trail stay consistent (`docs/design/DEVIATIONS.md`, stage 1). Dispatch-time rejections are recorded on the message and visible through the status and history endpoints. `POLICY_SUPPRESSED` exists in the error list but the submission path does not return it.
- Always return the structured `ApiError` with `code` and `correlationId`.
**Follow-ups:**
- When would you use 200 versus 204 for cancel?
- What is the difference between 401 and 403 in a multi-tenant API?
- Should validation errors list all fields at once?
**Pitfalls:**
- 200 for errors.
- 500 for client mistakes.
- 403 revealing that another tenant's resource exists.

### Q32. How do you protect a message submission API from oversized or malicious payloads?
**Tags:** REST API, Intermediate, Scenario
**Analogy:** A bouncer who checks bag size at the door before anyone enters the venue.
**Model answer:**
Clarify the threat model: accidental huge payloads (bad client loops), abusive clients, injection through template variables, and parser attacks (deeply nested JSON, duplicate keys).

Layers, outermost first:
- Edge/gateway: TLS, WAF rules, global request size cap, connection and read timeouts (slowloris).
- Application filter: `RequestSizeFilter` rejects bodies over 256 KB with 413 before parsing (CONTRACT sections 2 and 5). Check `Content-Length` and enforce while streaming for chunked requests.
- Parsing: Jackson limits (`StreamReadConstraints` for max nesting depth, string length, number length), reject unknown properties where appropriate, require `application/json`.
- Validation: Bean Validation with bounded sizes on every field (max length of `clientReference`, max number of variables and fallback channels, allow-listed enums), phone and email format checks; variables validated against the template's declared variable set (`VariableValidator`, CONTRACT section 2).
- Template safety: allow-listed `{{var}}` substitution only, no expression evaluation, HTML escaping for email HTML (CONTRACT section 2), so variables cannot execute code or inject markup.
- Rate limiting per client and a concurrency cap.
- Multipart bulk upload: stream the CSV, enforce max rows (`messaging.bulk.max-rows`), content-type and size, store outside the web root, scan filename and never trust it.

Never log full bodies or PII; log sizes and ids. Return the structured error without echoing input.

Measure: 413/400 rates by client, parsing time, rejected-by-limit counts, memory per request.
**Follow-ups:**
- How do you protect against a zip bomb in uploaded files?
- Why validate sizes of collections, not just strings?
- Where is HTML escaping applied and where not?
**Pitfalls:**
- Parsing first and checking size afterward.
- Accepting arbitrary metadata maps unbounded.
- Logging the rejected payload.

### Q33. A partner application suddenly sends 5,000 requests per second, ten times its normal rate. How does the platform stay healthy?
**Tags:** REST API, Staff, Scenario
**Analogy:** A restaurant that stops seating new parties when the kitchen backlog hits a limit, and politely tells them when to come back.
**Model answer:**
Clarify: is it a legitimate campaign, a retry storm from a failing client, or abuse? Do other tenants share the capacity? What is the client's contractual rate?

Layered defense:
1. Rate limit per client with a token bucket; excess gets `429` with `Retry-After` (CONTRACT section 5), which isolates the noisy client from others.
2. Backpressure on queue depth: when `messaging.backpressure.max-queue-depth` is exceeded, new submissions get `503` with `Retry-After` instead of letting memory and latency grow unboundedly (CONTRACT section 6).
3. Priority queues: `CRITICAL` and `HIGH` messages (for example security alerts) bypass the bulk of `LOW` traffic via RabbitMQ priorities (`x-max-priority=9`, CONTRACT sections 1 and 3.2).
4. Per-tenant fairness: separate quotas so one tenant's burst cannot starve the others; consider per-tenant queues or weighted consumption at larger scale.
5. Autoscale API pods on CPU/RPS, but remember the DB is the real bottleneck: protect it with a bounded pool and short transactions.
6. For legitimate large sends, steer the partner to the bulk endpoint (Spring Batch, throttled) rather than 5,000 individual POSTs.

Failure modes: retry storms (clients retry immediately on 429/503); require jitter and `Retry-After` in the client guide. If Redis is down the limiter falls back to local state, so per-instance limits multiply by instance count; alert on `messaging.redis.fallback`.

Measure: `messaging.ratelimit.rejected{scope}`, accept latency, queue depth and oldest age, DB pool pending, per-tenant share of traffic.
**Follow-ups:**
- How do you decide which tenant to shed first?
- Why not just autoscale everything?
- How would you notify the partner?
**Pitfalls:**
- Accepting everything and failing later.
- Global rather than per-client limits.
- No fairness between tenants.

## Email, SMS and Push Channels

### Q34. Explain the difference between a provider accepting an email, the email being delivered, and an unknown outcome. Why does it matter for the status model?
**Tags:** Email, Senior, Concept
**Analogy:** Handing a letter to the post office clerk (accepted) is not the same as the neighbour opening the mailbox (delivered); if the clerk's phone cuts off while you talk, you do not know whether the letter was taken.
**Model answer:**
Email delivery is multi-hop and asynchronous. Three distinct facts:
- Provider acceptance: the provider's API returned success (for example HTTP 202 with a provider message id). It means "I will try", not "the recipient got it". The platform records this as `SENT_TO_PROVIDER` and states acceptance never implies delivery (CONTRACT section 3.1).
- Delivery: the receiving mail server accepted the message (SMTP 250) as reported later by a webhook or event stream; this moves the message to `DELIVERED`. Even then "delivered" is not "read"; opens and clicks are separate, unreliable events (tracking pixels, privacy proxies).
- Failure: bounce (hard: invalid mailbox; soft: mailbox full, temporary), deferral, or complaint.
- Unknown: timeout or ambiguous response (connection reset after the request was sent) or no receipt within `deliveryReceiptTimeout`. The platform uses `DELIVERY_UNKNOWN` and reconciles later through callbacks or provider lookup rather than assuming failure or success (CONTRACT section 6).

Why it matters:
- Support and compliance need truthful states; telling a customer "delivered" because the API said 202 is wrong.
- Retrying on unknown risks duplicates, while giving up risks loss; the state machine makes the uncertainty explicit and routes it to reconciliation.
- Metrics must separate accept rate from delivery rate; SLOs usually target "accepted to terminal outcome within N minutes" for a percentage.

Late and duplicate events are normal: dedupe by `(provider_id, provider_event_id)` and apply a monotonic rank (CONTRACT section 3.4).
**Follow-ups:**
- Which events can arrive out of order?
- How long do you wait before marking unknown?
- Do open events count as delivery?
**Pitfalls:**
- Equating HTTP 202 from the provider with delivery.
- Treating timeout as failure and retrying immediately.
- Ignoring soft versus hard bounces.

### Q35. What are SPF, DKIM and DMARC, and how do they affect whether your emails reach the inbox?
**Tags:** Email, Intermediate, Concept
**Analogy:** SPF is the list of couriers allowed to deliver under your company name, DKIM is a tamper-proof seal on the envelope, and DMARC is the instruction telling receivers what to do with envelopes that fail both checks.
**Model answer:**
- SPF (Sender Policy Framework): a DNS TXT record listing the IP ranges and providers allowed to send mail for the domain in the envelope sender (`MAIL FROM`). Receivers check the connecting IP against it. Limit: 10 DNS lookups; breaks on simple forwarding.
- DKIM (DomainKeys Identified Mail): the sending system signs selected headers and the body with a private key; the public key sits in DNS under a selector (`selector._domainkey.example.com`). Receivers verify integrity and that the signing domain authorized the message. Rotate keys via selectors.
- DMARC: a DNS policy (`_dmarc.example.com`) requiring SPF or DKIM to pass and to align with the visible `From` domain. Policy is `none` (monitor), `quarantine` or `reject`, with aggregate (`rua`) and forensic reports. Roll out gradually: `p=none` to learn who sends as you, then enforce.

Operational impact:
- Mailbox providers increasingly require authentication, particularly for bulk senders; failing it means spam folder or rejection.
- Use a dedicated subdomain for marketing versus transactional mail so a reputation problem in campaigns does not damage password-reset or security-alert deliverability.
- Monitor bounce rate, complaint rate, and DMARC reports; keep lists clean via the suppression list (CONTRACT section 3.3).
- Include a working unsubscribe mechanism for marketing mail (one-click `List-Unsubscribe` headers are commonly expected).

The platform delegates DNS and signing configuration to the email provider and operations; the application's responsibility is clean lists, correct categories, and honoring suppression.
**Follow-ups:**
- What does "alignment" mean in DMARC?
- Why separate transactional and marketing domains?
- What is BIMI (briefly)?
**Pitfalls:**
- Jumping straight to `p=reject`.
- Confusing the envelope sender with the visible From.
- Sending marketing and security mail from one reputation.

### Q36. After a large marketing campaign, transactional emails start landing in spam. How do you investigate and remediate?
**Tags:** Email, Senior, Scenario
**Analogy:** A shop that sent a flood of junk flyers and now the postman suspects its important letters are junk as well.
**Model answer:**
Clarify: which domains and IP/provider pools, which mailbox providers are affected, onset time, and whether the campaign and the transactional mail share a sending domain or IP.

Investigation:
- Metrics: bounce rate (hard and soft), complaint rate, deferrals (`DEFERRED` events), delivered versus accepted ratio by recipient domain, and open rates as a weak signal.
- Authentication: verify SPF, DKIM and DMARC alignment still pass; check for a changed selector or a new sender not in SPF.
- Reputation: provider dashboards and postmaster tools for the domain and IP; blocklist checks.
- List quality: how old was the campaign list, was consent verified, how many invalid addresses; look at the `bulk_job_failure` and bounce/suppression data.
- Content: link domains, image-to-text ratio, missing unsubscribe.

Remediation:
- Stop or throttle the campaign (stop the bulk job; lower `rate_limit_per_sec`, CONTRACT section 7) and warm up slowly.
- Suppress hard bounces and complainers immediately (global suppression, CONTRACT section 3.3); removing them protects reputation.
- Separate streams: transactional on its own subdomain and IP pool with `CRITICAL/HIGH` priority.
- Consider provider failover for transactional traffic.
- Re-authenticate and ramp volume gradually.

Prevention: pre-send list hygiene, per-domain throttling, complaint-rate alarms, canary sends, and a rule that campaigns have a hard stop when bounce rate exceeds a threshold.

Measure: inbox placement tests, complaint rate below roughly 0.1 percent as an industry rule of thumb, bounce rate trending.
**Follow-ups:**
- How would you throttle per recipient domain?
- What is IP warm-up?
- How do you protect password-reset mail?
**Pitfalls:**
- Resending the campaign to "fix" delivery.
- Using one stream for all categories.
- Ignoring complaints.

### Q37. Hard bounces, soft bounces and complaints: how should the platform treat each?
**Tags:** Email, Intermediate, Concept
**Analogy:** A hard bounce is "no such address on this street"; a soft bounce is "nobody home right now"; a complaint is "please never knock on my door again".
**Model answer:**
- Hard bounce (permanent): invalid mailbox or domain, SMTP 5xx class. Never retry. The platform maps it to `FAILED`/`BOUNCED` and adds the address to the suppression list (CONTRACT section 6: hard-bounce adds suppression). Suppression blocks all categories on that channel (CONTRACT section 3.3 step 2) because repeated sends to dead addresses harm reputation and cost money.
- Soft bounce (transient): mailbox full, greylisting, temporary server issue, 4xx class. Retried by the provider and/or the platform with exponential backoff and jitter up to max attempts; after exhaustion consider the address degraded. A rule like "N soft bounces over M days becomes suppression" is common.
- Complaint (spam report via feedback loop): treat as an unsubscribe for marketing and a global suppression signal; this is severe for reputation. Event `UNSUBSCRIBED` or a complaint event maps to opt-out.
- Deferral: provider is still trying; status stays at rank 1 (CONTRACT section 3.4) until a terminal event.

Implementation details:
- Suppression stores a hash of the address (`address_hash`, unique per tenant and channel, CONTRACT section 4), not the plaintext, supporting data minimization.
- Callback processing is idempotent and rank-ordered so a late bounce does not overwrite delivered state incorrectly; conflicts are audited.
- Provide an operator path to remove a suppression (`DELETE .../suppressions/{channel}`), with audit, since some hard bounces are mistaken.

Measure: bounce rate by provider and domain, suppression additions per day, complaint rate.
**Follow-ups:**
- Should security alerts go to a suppressed address?
- How do you detect a spike of false bounces?
- What happens to fallback after a hard bounce?
**Pitfalls:**
- Retrying hard bounces.
- Plaintext address stored in suppression.
- Never un-suppressing mistaken entries.

### Q38. Explain SMS segmentation: GSM-7 versus UCS-2, and compute the number of segments for a given message.
**Tags:** SMS, Intermediate, Concept
**Analogy:** A postcard holds only a limited number of letters; a fancy alphabet needs bigger letters, so fewer fit, and long notes must be split across several postcards with page numbers.
**Model answer:**
SMS payload limits are 140 bytes per message. Encoding determines characters per message:
- GSM-7 (the default GSM 03.38 alphabet): 7 bits per character, 160 characters in a single segment. Extension table characters (for example `^ { } \ [ ] ~ | EUR`) cost 2 characters.
- UCS-2 (UTF-16 style): 16 bits per character, 70 characters in a single segment. Used whenever any character falls outside GSM-7 (emoji, many accented or non-Latin characters, smart quotes).
- Concatenated (multipart) messages need a User Data Header (6 bytes) for reassembly, reducing capacity: 153 GSM-7 characters or 67 UCS-2 characters per segment.

Examples:
- 160 GSM-7 characters: 1 segment. 161 characters: 2 segments (153 + 8 chars).
- 300 GSM-7 characters: ceil(300/153) = 2 segments.
- A 70-character message containing one emoji: UCS-2, 1 segment; 71 characters: 2 segments (67 + 4).
- A 150-character message with one curly apostrophe: switches to UCS-2, ceil(150/67) = 3 segments, tripling cost.

Implementation advice:
- Compute encoding and segment count in `TemplateRenderer`/validation before sending, expose it in the template preview endpoint (`POST /templates/preview`, CONTRACT section 5), and warn authors.
- Normalize smart quotes and dashes to GSM-7 equivalents where meaning allows.
- Count rendered output, not the template (variables change length).
- Cost, delivery latency and failure risk grow with segments; some carriers cap total segments.

Measure: average segments per message and percent UCS-2 by template.
**Follow-ups:**
- How are emoji counted in UCS-2 (surrogate pairs)?
- How would you test the segment calculator?
- Where do you enforce a max segment count?
**Pitfalls:**
- Assuming 160 characters always.
- Counting the template rather than the rendered text.
- Ignoring the extension-table double count.

### Q39. SMS spend jumps 40 percent after a template edit, but traffic volume is unchanged. What happened and how do you prevent it?
**Tags:** SMS, Intermediate, Scenario
**Analogy:** One fancy punctuation mark in the sentence made the post office charge triple for every postcard.
**Model answer:**
Clarify: which template, which version, and when did the cost change? Compare the average segments per message before and after, and compare provider billing records to our attempts.

Most likely cause: the new template version introduced a non-GSM-7 character (an emoji, a smart quote pasted from a document editor, an accented letter) or a longer text, switching encoding to UCS-2 and/or adding a segment. A single such character flips the entire message from 160 to 70 characters per segment.

Investigation:
- Query `template_version` history (and audit) to find the edit (CONTRACT sections 4 and 5; template history is exposed by `GET /templates/{id}/history`).
- Render with typical variables through the preview endpoint to see encoding and segments.
- Check variable values: a customer name containing non-GSM-7 characters can also flip individual messages; that is per-recipient, which explains partial increases.

Fixes:
- Revert or publish a corrected version (four-eyes approval applies, CONTRACT section 5 approve endpoint).
- Add a validation rule in the template submit step that computes worst-case segments and requires explicit acknowledgement above a threshold.
- Transliterate or normalize characters where legally and linguistically acceptable.
- Add an alert on average segments per message per template, and a per-template cost dashboard.

Trade-off: for some locales (Spanish, Hindi, Arabic) UCS-2 is unavoidable; then shorten the text, move detail to a link, or choose another channel.

Measure: segments per message, UCS-2 share, cost per thousand sends.
**Follow-ups:**
- Why does the approval workflow not catch this automatically?
- How would you handle personalised names?
- What alternative channels reduce cost?
**Pitfalls:**
- Looking only at volume.
- Blaming the provider's pricing.
- Removing all non-ASCII characters and damaging meaning.

### Q40. What are SMS opt-out and consent rules in general terms, and how should the platform enforce them?
**Tags:** SMS, Senior, Concept
**Analogy:** You can only knock on a neighbour's door for promotions if they invited you, and the moment they say "stop", you stop everywhere.
**Model answer:**
In many jurisdictions (the United States TCPA and carrier rules are an example; this is general information, not legal advice) marketing SMS requires prior express consent that is documented, and recipients must be able to opt out easily, with a keyword such as STOP honored promptly. Transactional or service messages the customer expects (a security code, a trade confirmation) are treated differently from promotional ones, but organizations still apply their own policy and legal review.

Platform enforcement (CONTRACT section 3.3):
- Category matters: `MARKETING` requires explicit channel opt-in and `marketingOptIn=true`; opt-out leads to `REJECTED(OPTED_OUT)`. Non-marketing categories ignore marketing opt-out but still respect hard suppressions.
- Order of checks is deliberate: recipient exists, global suppression, category rules, quiet hours, fallback evaluated independently (fallback never bypasses consent).
- Enforce at dispatch time as well as accept time, because consent can change between submission and sending (the consumer re-evaluates policy, CONTRACT section 6).
- Inbound STOP/HELP replies arrive as provider callbacks (`UNSUBSCRIBED` event type) and must update suppression and preferences idempotently and quickly.
- Keep an audit trail of consent changes (who, when, source) with `AuditService`, and keep records for the retention period legal requires.
- Quiet hours restrict marketing to reasonable times in the recipient's time zone.

Pitfalls to avoid: using one preference flag for everything, sending a "you have unsubscribed" message as marketing, and replaying old campaigns to people who opted out after the original send.

Measure: opt-out processing latency, suppression hits, complaints.
**Follow-ups:**
- What if the opt-out arrives while the message is queued?
- How do you honor opt-out across channels?
- How do you prove consent in an audit?
**Pitfalls:**
- Checking consent only at submission.
- Treating all categories the same.
- No audit trail.

### Q41. How do APNs and FCM device tokens work, and what lifecycle issues do they create?
**Tags:** Push, Intermediate, Concept
**Analogy:** A device token is a mailbox number that the phone maker gives your app on that phone; if the person reinstalls the app, the old number may stop working.
**Model answer:**
Push notifications go through the platform vendor: APNs for Apple devices and FCM for Android (and web). The app registers with the OS and receives an opaque device token (APNs) or registration token (FCM) which the backend stores per device (`recipient.push_token`, CONTRACT section 4, in a real system a separate device table supporting many devices per user).

Lifecycle issues:
- Rotation: tokens change on reinstall, restore, OS update, or after long inactivity. The app should re-send its token on each launch.
- Invalidation: APNs returns errors such as `BadDeviceToken`/`Unregistered` (HTTP 410) and FCM returns `UNREGISTERED`/`NOT_FOUND`. Treat these as permanent failures for that token: mark the token invalid and stop sending (a permanent failure outcome, CONTRACT section 3.2).
- Multi-device and multi-app: map user to many tokens; fan out per token; track `app_id`, platform and last-seen.
- Environments: APNs sandbox and production tokens differ; mixing them yields `BadDeviceToken`.
- Authentication: APNs token-based auth (JWT with a signing key) or certificates; FCM uses OAuth2 service-account tokens. Keep these in a secrets store and rotate.
- Payload limits: roughly 4 KB for APNs, 4 KB for FCM data; collapse key / `apns-collapse-id` replace older undelivered notifications; TTL (`ttlSeconds`, CONTRACT section 5) drops expired messages; priority hints affect battery-friendly delivery.
- Delivery is best effort: the vendor accepts quickly, device may be offline, notifications can be disabled in OS settings, and delivery receipts are limited.

Push retries are safe with a collapse key because a duplicate replaces rather than duplicates (CONTRACT section 6 allows retry for push).
**Follow-ups:**
- How do you clean stale tokens?
- What is the difference between notification and data messages?
- How do you test push without devices?
**Pitfalls:**
- Treating the token as permanent.
- Retrying on `Unregistered`.
- Putting sensitive data in the push payload.

### Q42. Push error rates climb: many `Unregistered` and `BadDeviceToken` responses. What do you do?
**Tags:** Push, Intermediate, Scenario
**Analogy:** A mailman reports that many addresses on your delivery list no longer exist; you must update the address book instead of re-delivering.
**Model answer:**
Clarify: since when, which platform (iOS or Android), which app version, and whether a release or environment change preceded it (for example a build switched from APNs sandbox to production, or the FCM project/credentials changed).

Triage:
- Break down errors by vendor error code, app version and token age. `Unregistered` clustered on old tokens means normal churn; `BadDeviceToken` clustered on a version or environment points to a configuration bug.
- Check `messaging.provider.calls{provider,channel,outcome}` for the outcome mix and whether circuit breakers opened (they should not open for permanent per-token errors, since the service is healthy; classify permanent per-token errors separately from provider-wide failures).

Actions:
- Mark failing tokens invalid immediately and stop sending; never retry them (permanent failure, CONTRACT section 3.2).
- Fix configuration errors (credentials, bundle id, environment) and re-test with a canary recipient.
- Let the fallback path run for critical alerts where the recipient consented to another channel (CONTRACT section 3.3 step 5), evaluated independently through policy.
- Ask the app team to re-register tokens on launch and on token refresh callbacks.

Prevention: periodic pruning of tokens not seen in N days, token validation on registration, canary pushes after releases, dashboards on invalid-token ratio, and alerts when it exceeds a baseline (for example 2 percent).

Cost angle: sending to dead tokens wastes capacity and can hurt throttling reputation with the vendor.

Measure: invalid-token ratio, successful delivery rate by app version, re-registration lag.
**Follow-ups:**
- Why should a circuit breaker ignore per-token errors?
- How do you handle multiple tokens per user?
- What if the vendor itself is down?
**Pitfalls:**
- Retrying invalid tokens.
- Counting per-token errors as provider outages.
- No token cleanup process.

## Policy, Fallback and Provider Abstraction

### Q43. Marketing messages must not be sent between 21:00 and 08:00 recipient local time. How do you implement quiet hours correctly, including daylight saving?
**Tags:** Policy, Senior, Scenario
**Analogy:** Not ringing the doorbell at night, and remembering that when clocks change the "night" moves by an hour for that house.
**Model answer:**
Clarify: which categories are covered (marketing and low-priority service per CONTRACT section 3.3 step 4, while `SECURITY_ALERT` always sends and `TRANSACTIONAL` sends unless SMS/PUSH at `LOW`), what happens to messages inside the window (deferral, not rejection), and what the maximum acceptable delay is.

Design:
- Store the recipient's IANA time zone id (`recipient.time_zone`, for example `America/New_York`), never a fixed UTC offset, because offsets change with daylight saving time.
- Evaluate with `ZonedDateTime` using `java.time` and `ZoneId`; compute the window end in the recipient zone and convert to UTC for `scheduled_at`. Handle windows that cross midnight (21:00 to 08:00).
- Deferral keeps status `VALIDATED` with `scheduled_at` moved to the window end; `ScheduledDispatchPoller` publishes when due (CONTRACT sections 2 and 3.1). Re-evaluate at dispatch, since preferences or time can change.
- DST edge cases: a local time may not exist (spring forward gap) or occur twice (fall back overlap). Use `ZonedDateTime.ofLocal` rules (gap shifts forward, overlap picks earlier offset by default) and test those dates explicitly.
- Missing time zone: use a documented default and record it.
- Thundering herd: many deferred messages become due at 08:00 for a time zone; smooth with jitter or rate limits so the provider limiter and `messaging.dispatch.delay` stay healthy.
- Inject a `Clock` for deterministic tests (CONTRACT section 2).

Measure: deferred count, deferral duration, burst size at window end, violations (should be zero; alert on any marketing send inside local quiet hours).
**Follow-ups:**
- What if the message expires during quiet hours?
- How do you test DST transitions?
- Does a user-specific quiet window override the default?
**Pitfalls:**
- Using UTC offsets or server time zone.
- Rejecting instead of deferring.
- Not rechecking at dispatch time.

### Q44. Walk through the policy evaluation order for a message and why the order matters.
**Tags:** Policy, Senior, Concept
**Analogy:** Airport security: ID check first, then the banned-items list, then your ticket class rules, and only then your gate time.
**Model answer:**
The order in CONTRACT section 3.3 is deliberate; each step either stops the flow or passes to the next:
1. Recipient must exist for the tenant and not be deleted (also enforces tenant isolation).
2. Global suppression list (hard bounce, complaint, regulatory) blocks all categories on that channel: `REJECTED(SUPPRESSED)`. It is first among rules because it is the strongest, legal/reputational constraint and cheap to check.
3. Category rules: marketing needs explicit channel opt-in and `marketingOptIn=true`, else `REJECTED(OPTED_OUT)`. Non-marketing categories ignore the marketing opt-out; per-channel `enabled` applies only to `SERVICE`.
4. Quiet hours: defer marketing and low/normal service; security alerts always send; transactional sends unless SMS/PUSH at LOW.
5. Fallback channels each evaluated independently through steps 1 to 4: fallback never bypasses consent.
6. Idempotency (accept-time concern).

Why the order matters:
- Cheaper and more final checks first, and rejecting before deferring avoids queueing a message that can never be sent.
- If quiet hours ran before suppression you might schedule a message you must reject later, wasting resources and leaving misleading states.
- The audit reason (`status_reason`) reflects the first blocking rule, which helps support explain outcomes.

Where it runs: at accept time for fast feedback and at dispatch time because state changes in between (CONTRACT section 6). Implementation: `PolicyService` as a pure, unit-testable function returning a decision (ALLOW, DEFER(until), REJECT(reason)), with table-driven tests per rule combination.

Measure: rejection counts by reason, deferrals, and a regression test per rule row.
**Follow-ups:**
- Where would a regional regulation rule slot in?
- How do you make the decision explainable to support?
- Why re-evaluate at dispatch?
**Pitfalls:**
- Checking consent only once.
- Letting fallback skip checks.
- Mixing policy with transport code.

### Q45. The primary SMS provider fails permanently for a recipient; the request allowed an EMAIL fallback. How should fallback behave?
**Tags:** Policy, Senior, Scenario
**Analogy:** If the phone line is dead you may write a letter, but only if the person agreed to receive letters.
**Model answer:**
Clarify: is the failure permanent for the recipient (invalid number) or a provider-wide outage? Was fallback requested explicitly by the sender (`fallbackChannels`, CONTRACT section 5)? Is the message time-sensitive?

Behavior per CONTRACT sections 3.3 and 6:
- Fallback is attempted only on `PERMANENT_FAILURE` or `RETRIES_EXHAUSTED` of the primary, not on transient errors (those go to retry) and not on ambiguous outcomes (risk of sending on both channels).
- Each fallback channel runs through policy steps 1 to 4 independently: suppression, opt-in, quiet hours for that channel. A recipient who opted out of email never gets the fallback email.
- A fallback creates a new attempt on a new channel recorded on the same message (`channel_used`), keeping one message identity for support and one idempotency scope.
- A hard bounce adds suppression for the failed address and channel, so future sends do not repeat it.

Failure modes:
- Duplicate across channels: if the SMS outcome was actually unknown and the message was later delivered, a fallback email produces a double notification; hence no fallback on `DELIVERY_UNKNOWN` until reconciliation settles it.
- Content mismatch: the template must exist for the fallback channel (templates are per channel, `UNIQUE(tenant_id,code,channel)`, CONTRACT section 4); otherwise mark the fallback rejected with reason.
- Cost or tone: marketing should usually not fall back across channels.

Measure: fallback rate per channel pair, fallback success, time to delivery via fallback.

<!-- in-project:b41 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/messaging/DispatchConsumer.java`
>
> The request carries at most two fallback channels (`@Size(max = 2)`). The fallback gets a fresh attempt budget and a new idempotency reference group (`messageId:2`, ...).
>
> ```java
> private Optional<Channel> chooseFallback(MessageRequest m, Recipient recipient) {
>     List<Channel> all = m.fallbackList();
>     if (recipient == null || m.getFallbackIndex() >= all.size()) {
>         return Optional.empty();
>     }
>     List<Channel> remaining = all.subList(m.getFallbackIndex(), all.size());
>     for (PolicyService.FallbackDecision fd : policy.evaluateFallbacks(recipient, remaining, m.getCategory(),
>             m.getPriority(), clock.instant())) {
>         if (fd.decision().isAllowed()) { // a deferred or rejected fallback is skipped: consent is never bypassed
>             return Optional.of(fd.channel());
>         }
>     }
>     return Optional.empty();
> }
> ```

**Follow-ups:**
- Should security alerts fall back automatically?
- What if both channels succeed?
- How do you display this in the message history?
**Pitfalls:**
- Fallback ignoring consent.
- Falling back on timeouts.
- Treating fallback as a separate unrelated message.

### Q46. You must add a new SMS vendor next quarter. How do you design the provider abstraction so adding it is low risk?
**Tags:** Architecture, Senior, Scenario
**Analogy:** A universal power adapter: the appliance (platform) keeps one plug shape, and each country's socket (vendor) gets its own small adapter.
**Model answer:**
Clarify: why a new vendor (cost, coverage, redundancy, features), migration approach (shadow, percentage rollout), and compliance requirements (data residency, contracts).

Design (CONTRACT section 2 `provider/`): ports `EmailProvider`, `SmsProvider`, `PushNotificationProvider`, a `ProviderRegistry`, normalized `ProviderResult` with `ProviderOutcome` (ACCEPTED, TRANSIENT_FAILURE, PERMANENT_FAILURE, RATE_LIMITED, TIMEOUT, AMBIGUOUS), and `ResilientProviderInvoker` that adds timeout and circuit breaker around any adapter. The contract adds `AcmeSmsProvider` as the worked "new provider" example.

Steps to add a vendor:
1. Implement the port: map vendor HTTP codes and error bodies to the normalized outcomes. This mapping is the riskiest part: vendors differ on what 5xx, 429 and timeouts mean.
2. Implement `lookup(idempotencyRef)` for reconciliation if the vendor supports it (CONTRACT section 6); pass `idempotencyRef = messageId:attemptGroup` where supported.
3. Add a per-provider callback mapper in `EventNormalizer` and a signature scheme (`X-Provider-Key-Id`, HMAC) (CONTRACT sections 1 and 2).
4. Add `provider_config` row (rate limit, timeout, priority order, enabled flag), metrics tagged by provider.
5. Test with the simulator and contract tests of the mapper with recorded sample payloads (synthetic, not real).
6. Roll out by routing weight (shadow, 1 percent, ramp) and keep the old provider as failover.

Risks: vendor semantics for delivery receipts differ (some report "delivered" only to the carrier), segment counting differences affect cost, sender-id registration lead times. Measure per provider: accept rate, delivery rate, latency p95, cost per delivered message.
**Follow-ups:**
- How would you route between two SMS providers?
- How do you test the callback mapper?
- What happens to in-flight messages when you disable a provider?
**Pitfalls:**
- Leaking vendor-specific types into the domain.
- Skipping outcome mapping tests.
- Big-bang cutover.
