# Diagram Atlas

Sixteen pictures worth drawing from memory on a whiteboard. Each has a few lines of reading guidance. The diagrams are drawn by the PDF engine from specifications, so they are vector graphics and carry only what the text says. Numbers in the retry chart are computed from the configured formula, not measured.

## 1. Garbage collector heap regions (G1)

G1 divides the heap into equal regions and gives each a role. Young collections copy live objects out of Eden and survivor regions; objects that survive enough collections are promoted to old regions; objects of half a region or more are allocated directly in humongous regions, which is why a service that allocates large arrays can suffer. Memory outside the heap also counts toward the container limit.

```diagram
{"type":"boxes-and-arrows","direction":"LR","caption":"G1 heap regions and the memory that lives outside the heap",
 "lanes":[
  {"id":"young","label":"Young generation (regions)","color":"green","nodes":[{"id":"eden","label":"Eden regions","sub":"new objects"},{"id":"surv","label":"Survivor regions","sub":"live after a young GC"}]},
  {"id":"old","label":"Old generation (regions)","color":"orange","nodes":[{"id":"oldr","label":"Old regions","sub":"promoted, long lived"},{"id":"huge","label":"Humongous regions","sub":"objects >= half a region"}]},
  {"id":"off","label":"Outside the heap (counts toward the pod limit)","color":"purple","nodes":[{"id":"meta","label":"Metaspace","sub":"class metadata"},{"id":"stack","label":"Thread stacks","sub":"one per platform thread"},{"id":"direct","label":"Direct buffers, code cache","sub":"NIO, JIT"}]}],
 "edges":[{"from":"eden","to":"surv","label":"copy live"},{"from":"surv","to":"oldr","label":"promote after N GCs"},{"from":"eden","to":"huge","style":"dashed"},{"from":"oldr","to":"meta","style":"dashed","label":"must fit the limit"}]}
```

<!-- pagebreak -->
## 2. Thread pool and bounded queue

A pool has core threads, a queue and a rejection policy. The order matters: a new task goes to a core thread, then to the queue, and only when the queue is full to an extra thread up to the maximum; beyond that the rejection policy decides. The project fixes core equal to maximum and bounds the queue, so the only open question is what happens when it is full.

```diagram
{"type":"boxes-and-arrows","direction":"LR","caption":"Bounded thread pool: submit, queue, workers, rejection",
 "lanes":[
  {"id":"p","label":"Producers","color":"blue","nodes":[{"id":"loop","label":"Bus consumer loop","sub":"prefetch 16"},{"id":"call","label":"Provider call submit","sub":"per message"}]},
  {"id":"q","label":"Pool","color":"green","nodes":[{"id":"queue","label":"Bounded queue","sub":"ArrayBlockingQueue or prefetch sized"},{"id":"work","label":"Fixed workers","sub":"dispatch-1..8 or provider-call-n"}]},
  {"id":"r","label":"When the queue is full","color":"red","nodes":[{"id":"cr","label":"CallerRunsPolicy","sub":"dispatch pool: slows the loop"},{"id":"ab","label":"AbortPolicy","sub":"provider and bulk pools: fail fast"}]}],
 "edges":[{"from":"loop","to":"queue","label":"execute"},{"from":"call","to":"queue"},{"from":"queue","to":"work","label":"take"},{"from":"queue","to":"cr","style":"dashed","label":"full"},{"from":"queue","to":"ab","style":"dashed","label":"full"}]}
```

<!-- pagebreak -->
## 3. Transaction propagation

`REQUIRED` joins the caller's transaction; `REQUIRES_NEW` suspends it and runs an independent one that commits or rolls back on its own; `MANDATORY` insists that one already exists. The idempotency claim uses `REQUIRES_NEW` so a concurrent duplicate can see it before the business transaction ends, and the completion step uses `MANDATORY` so it flips the record atomically with the business change.

```diagram
{"type":"sequence","autonumber":true,"caption":"Propagation in the idempotent submit path (simplified from IdempotencyService and NotificationService)",
 "participants":[{"id":"api","label":"MessageController"},{"id":"idem","label":"IdempotencyService"},{"id":"svc","label":"NotificationService"},{"id":"db","label":"Database"}],
 "steps":[
  {"from":"api","to":"idem","text":"begin(tenant, key, hash)"},
  {"note":"no transaction is open here by design","over":"idem"},
  {"from":"idem","to":"db","text":"REQUIRES_NEW: insert IN_PROGRESS, commit"},
  {"from":"idem","to":"api","text":"claimId","kind":"return"},
  {"from":"api","to":"svc","text":"submit(request) @Transactional (REQUIRED)"},
  {"from":"svc","to":"db","text":"insert message, outbox, audit"},
  {"from":"svc","to":"idem","text":"complete(...) MANDATORY joins the transaction"},
  {"from":"idem","to":"db","text":"flip record to COMPLETED"},
  {"note":"commit: message, outbox, audit and COMPLETED become visible together","over":["svc","db"]},
  {"alt":"business transaction fails","steps":[{"from":"api","to":"idem","text":"release(claimId) REQUIRES_NEW"}],"else":{"label":"success","steps":[{"from":"api","to":"api","text":"respond 202"}]}}
 ]}
```

<!-- pagebreak -->
## 4. Isolation levels and anomalies

| Isolation level | Dirty read | Non-repeatable read | Phantom read | Lost update | Write skew |
|---|---|---|---|---|---|
| READ UNCOMMITTED | Possible (not offered by PostgreSQL) | Possible | Possible | Possible | Possible |
| READ COMMITTED (PostgreSQL and Oracle default) | Prevented | Possible | Possible | Possible unless a CAS update or lock is used | Possible |
| REPEATABLE READ (snapshot in PostgreSQL) | Prevented | Prevented | Prevented in PostgreSQL, possible in the SQL standard | Detected: the second writer fails | Possible |
| SERIALIZABLE | Prevented | Prevented | Prevented | Prevented | Prevented (transactions may be aborted and must be retried) |

How the platform copes at READ COMMITTED: status changes are `UPDATE ... WHERE id = ? AND status = ?` and the affected row count says who won; humans editing preferences use an optimistic `@Version`; uniqueness is enforced by constraints. SERIALIZABLE is not used because of retry complexity.

<!-- pagebreak -->
## 5. Kafka versus RabbitMQ

```diagram
{"type":"boxes-and-arrows","direction":"LR","caption":"Log-based broker versus queue-based broker",
 "lanes":[
  {"id":"k","label":"Kafka: partitioned log","color":"purple","nodes":[{"id":"kp","label":"Producer","sub":"key chooses partition"},{"id":"kl","label":"Topic partitions","sub":"append-only, retained, replayable"},{"id":"kc","label":"Consumer group","sub":"one consumer per partition, offsets"}]},
  {"id":"r","label":"RabbitMQ: exchanges and queues","color":"orange","nodes":[{"id":"rp","label":"Publisher","sub":"confirms"},{"id":"rx","label":"Exchange","sub":"routing keys, bindings"},{"id":"rq","label":"Queues","sub":"priority, per-message TTL, DLX"},{"id":"rc","label":"Competing consumers","sub":"ack, nack, prefetch"}]}],
 "edges":[{"from":"kp","to":"kl"},{"from":"kl","to":"kc","label":"pull by offset"},{"from":"rp","to":"rx"},{"from":"rx","to":"rq","label":"route"},{"from":"rq","to":"rc","label":"push"}]}
```

| Question | Kafka | RabbitMQ |
|---|---|---|
| Replay old events | Native (retention) | Not native |
| Per-message priority and TTL | Not native | Native |
| Ordering | Per partition | Per queue (lost with competing consumers and redelivery) |
| Typical strength | Streams, analytics, very large fan-out | Task queues, routing, delays, priorities |
| Platform choice | Behind the `MessageBus` port, not used | Primary (ADR-0001): priority queues, TTL and dead-letter exchanges |

<!-- pagebreak -->
## 6. Cloud Foundry versus Kubernetes

```diagram
{"type":"boxes-and-arrows","direction":"LR","caption":"Deployment model comparison",
 "lanes":[
  {"id":"cf","label":"Cloud Foundry (application platform)","color":"blue","nodes":[{"id":"cfp","label":"cf push","sub":"jar + manifest, buildpack builds the droplet"},{"id":"cfr","label":"Diego cells run instances","sub":"health check, routes, services binding"},{"id":"cfs","label":"Marketplace services","sub":"VCAP_SERVICES"}]},
  {"id":"k8","label":"Kubernetes (container orchestrator)","color":"green","nodes":[{"id":"kd","label":"Image + manifests","sub":"you build the image"},{"id":"kp2","label":"Deployment, Service, Ingress","sub":"probes, HPA, PDB, network policy"},{"id":"kc2","label":"ConfigMap and Secret","sub":"env and volumes"}]}],
 "edges":[{"from":"cfp","to":"cfr"},{"from":"cfr","to":"cfs","label":"bind"},{"from":"kd","to":"kp2"},{"from":"kp2","to":"kc2","label":"inject"}]}
```

| Aspect | Cloud Foundry | Kubernetes |
|---|---|---|
| Unit | Application | Container and pod |
| Who builds the runtime | Buildpack | Your Dockerfile |
| Developer effort | Low, opinionated | Higher, flexible |
| Rollout | Rolling or blue-green with route mapping | Rolling update, canary and blue-green by tooling |
| Scaling | `cf scale` and autoscaler | HPA, KEDA |
| Project assets | `cloud-foundry/manifest.yml` | `infrastructure/kubernetes`, Helm chart |
| Status here | Not executed | Not executed |

<!-- pagebreak -->
## 7. OAuth 2.0 authorization code flow with PKCE, and the resource server

```diagram
{"type":"sequence","autonumber":true,"caption":"Authorization code with PKCE for the Angular console; the API is the resource server",
 "participants":[{"id":"user","label":"User"},{"id":"spa","label":"Angular console"},{"id":"idp","label":"Identity provider"},{"id":"api","label":"Messaging API"}],
 "steps":[
  {"from":"spa","to":"spa","text":"create code_verifier, code_challenge = S256(verifier)"},
  {"from":"spa","to":"idp","text":"redirect: /authorize (client_id, scope, state, code_challenge)"},
  {"from":"user","to":"idp","text":"authenticate and consent"},
  {"from":"idp","to":"spa","text":"redirect with code and state","kind":"return"},
  {"from":"spa","to":"idp","text":"POST /token (code, code_verifier)"},
  {"from":"idp","to":"spa","text":"access token (JWT), optional id token","kind":"return"},
  {"from":"spa","to":"api","text":"GET /api/v1/messages  Authorization: Bearer ..."},
  {"from":"api","to":"api","text":"validate JWT (signature via JWKS, iss, exp, roles, tenant)"},
  {"from":"api","to":"spa","text":"200 or 401 or 403","kind":"return"}
 ]}
```

Service-to-service callers (the `SENDER` role) use the client credentials grant instead: no user, a confidential client secret or mTLS, a narrow scope. In this repository the local profile mints tokens with `POST /dev/token`; that endpoint exists only when `messaging.security.dev-token-enabled=true`.

<!-- pagebreak -->
## 8. JWT validation steps

```diagram
{"type":"boxes-and-arrows","direction":"TB","caption":"What a resource server must check, in order",
 "lanes":[
  {"id":"a","label":"Parse","color":"blue","nodes":[{"id":"s1","label":"1. Extract bearer token","sub":"Authorization header, not the URL"},{"id":"s2","label":"2. Read header","sub":"alg from configuration, kid selects key"}]},
  {"id":"b","label":"Cryptography","color":"green","nodes":[{"id":"s3","label":"3. Verify signature","sub":"JWKS key, expected algorithm only"},{"id":"s4","label":"4. Check time claims","sub":"exp, nbf, small clock skew"}]},
  {"id":"c","label":"Claims","color":"orange","nodes":[{"id":"s5","label":"5. Check issuer and audience","sub":"iss must match, aud contains this API"},{"id":"s6","label":"6. Map roles and scope","sub":"roles to ROLE_, scope to SCOPE_"}]},
  {"id":"d","label":"Application rules","color":"purple","nodes":[{"id":"s7","label":"7. Require tenant and subject","sub":"reject token without a valid tenant claim"},{"id":"s8","label":"8. Authorise per request","sub":"@PreAuthorize plus tenant ownership, 404 if foreign"}]}],
 "edges":[{"from":"s1","to":"s2"},{"from":"s2","to":"s3"},{"from":"s3","to":"s4"},{"from":"s4","to":"s5"},{"from":"s5","to":"s6"},{"from":"s6","to":"s7"},{"from":"s7","to":"s8"}]}
```

Common mistakes: accepting `alg: none` or letting the token pick the algorithm, skipping the audience check, trusting `tenant` from a header, caching the JWKS forever (no key rotation), and treating a valid signature as authorisation.

<!-- pagebreak -->
## 9. Retry backoff with jitter

The project computes the ceiling as `min(cap, base x 2^(attempt - 1))` with base 1 second and cap 60 seconds, then multiplies by a random factor between 0.5 and 1.0. Five attempts are allowed by default, so the later bars show the cap rather than a normal run.

```diagram
{"type":"bar","title":"Backoff before the next attempt (seconds)","unit":"seconds","caption":"Computed from RetryPolicy with base 1000 ms and cap 60000 ms: ceiling and the lower end of the jitter range",
 "categories":["after attempt 1","2","3","4","5","6","7"],
 "series":[{"name":"Ceiling","values":[1,2,4,8,16,32,60]},{"name":"Minimum with jitter","values":[0.5,1,2,4,8,16,30]}]}
```

Why jitter: without it, thousands of messages that failed together retry together and re-create the overload. Why a cap: an unbounded exponent would park messages for hours. Why classification matters: only transient failures and rate limiting are retried; a permanent failure is not, and an uncertain outcome is reconciled rather than blindly retried.

<!-- pagebreak -->
## 10. Message lifecycle state machine

```diagram
{"type":"state","caption":"Message lifecycle (MessageStateMachine table)","states":[{"id":"ACCEPTED","initial":true,"pos":[1,0]},{"id":"VALIDATED","pos":[1,1]},{"id":"QUEUED","pos":[1,2]},{"id":"PROCESSING","pos":[1,3]},{"id":"RETRY_PENDING","kind":"warn","pos":[0,3]},{"id":"SENT_TO_PROVIDER","pos":[1,4]},{"id":"DELIVERY_UNKNOWN","label":"DELIVERY UNKNOWN","kind":"warn","pos":[2,4]},{"id":"DELIVERED","kind":"ok","pos":[1,5]},{"id":"FAILED","kind":"error","pos":[2,5]},{"id":"DEAD_LETTERED","kind":"error","pos":[3,5]},{"id":"REJECTED","kind":"error","pos":[3,1]},{"id":"CANCELLED","kind":"neutral","pos":[0,1]}],"transitions":[{"from":"ACCEPTED","to":"VALIDATED"},{"from":"ACCEPTED","to":"REJECTED"},{"from":"VALIDATED","to":"QUEUED"},{"from":"VALIDATED","to":"CANCELLED"},{"from":"VALIDATED","to":"REJECTED"},{"from":"QUEUED","to":"PROCESSING"},{"from":"QUEUED","to":"CANCELLED"},{"from":"PROCESSING","to":"SENT_TO_PROVIDER"},{"from":"PROCESSING","to":"RETRY_PENDING"},{"from":"PROCESSING","to":"FAILED"},{"from":"PROCESSING","to":"DELIVERY_UNKNOWN"},{"from":"PROCESSING","to":"REJECTED"},{"from":"RETRY_PENDING","to":"QUEUED"},{"from":"RETRY_PENDING","to":"DEAD_LETTERED"},{"from":"RETRY_PENDING","to":"CANCELLED"},{"from":"SENT_TO_PROVIDER","to":"DELIVERED"},{"from":"SENT_TO_PROVIDER","to":"FAILED"},{"from":"SENT_TO_PROVIDER","to":"DELIVERY_UNKNOWN"},{"from":"DELIVERY_UNKNOWN","to":"DELIVERED"},{"from":"DELIVERY_UNKNOWN","to":"FAILED"},{"from":"FAILED","to":"DEAD_LETTERED"},{"from":"FAILED","to":"QUEUED"},{"from":"DEAD_LETTERED","to":"QUEUED"}]}
```

Reading rules: `DELIVERED`, `REJECTED` and `CANCELLED` have no outgoing edge. `FAILED` and `DEAD_LETTERED` return to `QUEUED` only by an operator requeue or replay (and by fallback or reconciliation, which use consecutive legal transitions in one transaction, recorded in DEVIATIONS). A terminal receipt that arrives while a message is still `PROCESSING` is also applied (`canTransitionViaReceipt`).

<!-- pagebreak -->
## 11. Idempotency decision flow

```diagram
{"type":"boxes-and-arrows","direction":"TB","caption":"POST /messages with an Idempotency-Key (IdempotencyService.begin)",
 "lanes":[
  {"id":"a","label":"Request","color":"blue","nodes":[{"id":"n1","label":"Request with Idempotency-Key","sub":"format validated, tenant from token"}]},
  {"id":"b","label":"Look up","color":"teal","nodes":[{"id":"n2","label":"Cached completed response?","sub":"fast path, never trusted for correctness"},{"id":"n3","label":"Try to claim the key","sub":"REQUIRES_NEW insert, unique (tenant, key)"}]},
  {"id":"c","label":"Decide","color":"orange","nodes":[{"id":"n4","label":"Claim won","sub":"proceed with business transaction"},{"id":"n5","label":"Same hash, COMPLETED","sub":"replay: 200 + Idempotent-Replay"},{"id":"n6","label":"Same hash, IN_PROGRESS","sub":"409, unless the claim expired"},{"id":"n7","label":"Different hash","sub":"422 IDEMPOTENCY_KEY_REUSED"}]},
  {"id":"d","label":"Finish","color":"green","nodes":[{"id":"n8","label":"complete() in the same transaction","sub":"202 Accepted for the first request"}]}],
 "edges":[{"from":"n1","to":"n2"},{"from":"n2","to":"n3","label":"miss"},{"from":"n2","to":"n5","label":"cache hit","style":"dashed"},{"from":"n3","to":"n4","label":"claimed"},{"from":"n3","to":"n5","label":"COMPLETED","style":"dashed"},{"from":"n3","to":"n6","label":"IN_PROGRESS","style":"dashed"},{"from":"n3","to":"n7","label":"other hash","style":"dashed"},{"from":"n4","to":"n8"}]}
```

<!-- pagebreak -->
## 12. Outbox publish and dispatch

```diagram
{"type":"sequence","autonumber":true,"caption":"From accepted request to provider call (OutboxPublisher and DispatchConsumer)",
 "participants":[{"id":"api","label":"API transaction"},{"id":"ob","label":"OutboxPublisher"},{"id":"bus","label":"MessageBus"},{"id":"con","label":"DispatchConsumer"},{"id":"prov","label":"Provider"}],
 "steps":[
  {"from":"api","to":"api","text":"commit: message VALIDATED + outbox NEW + audit"},
  {"from":"ob","to":"ob","text":"markSent(CAS), VALIDATED to QUEUED, publish (one transaction)"},
  {"from":"ob","to":"bus","text":"publish envelope (no content, no PII)"},
  {"from":"bus","to":"con","text":"deliver (manual ack)"},
  {"from":"con","to":"con","text":"checks: cancel, expiry, policy, circuit, token bucket"},
  {"from":"con","to":"con","text":"claim: processed_message insert + CAS QUEUED to PROCESSING"},
  {"from":"con","to":"prov","text":"send via ResilientProviderInvoker (no DB transaction open)"},
  {"alt":"accepted","steps":[{"from":"con","to":"con","text":"SENT_TO_PROVIDER; ack"}],"else":{"label":"transient failure","steps":[{"from":"con","to":"bus","text":"requeue with backoff and jitter","kind":"async"}]}},
  {"note":"A crash after the claim is repaired by StuckMessageSweeper to DELIVERY_UNKNOWN, then reconciliation","over":["con","prov"]}
 ]}
```

<!-- pagebreak -->
## 13. Webhook verification

```diagram
{"type":"sequence","autonumber":true,"caption":"Provider callback: authenticate, deduplicate, order, apply",
 "participants":[{"id":"p","label":"Provider"},{"id":"c","label":"CallbackService"},{"id":"g","label":"ReplayGuard"},{"id":"db","label":"Database"}],
 "steps":[
  {"from":"p","to":"c","text":"POST /api/v1/callbacks/{provider} X-Signature, X-Event-Id, raw body"},
  {"from":"c","to":"c","text":"verify HMAC over t.rawBody, constant time, 5 minute tolerance"},
  {"alt":"signature problem","steps":[{"from":"c","to":"p","text":"401 INVALID_SIGNATURE (always the same body)","kind":"return"}],"else":{"label":"valid","steps":[
    {"from":"c","to":"g","text":"isReplay(provider:eventId)?"},
    {"alt":"seen","steps":[{"from":"c","to":"p","text":"200 {result: replayed}","kind":"return"}],"else":{"label":"new","steps":[
      {"from":"c","to":"db","text":"normalise, insert delivery_event (unique provider, event id)"},
      {"from":"c","to":"db","text":"rank rule + CAS status change"},
      {"from":"c","to":"g","text":"markSeen after success"},
      {"from":"c","to":"p","text":"2xx","kind":"return"}]}}]}}
 ]}
```

<!-- pagebreak -->
## 14. CI/CD flow

```diagram
{"type":"boxes-and-arrows","direction":"LR","caption":"Pipeline stages (from the Jenkinsfile; not executed here)",
 "lanes":[
  {"id":"b","label":"Build and test (parallel)","color":"blue","nodes":[{"id":"bb","label":"Backend verify"},{"id":"bf","label":"Frontend lint, test, build"},{"id":"bs","label":"Static analysis"}]},
  {"id":"s","label":"Security and integration (parallel)","color":"red","nodes":[{"id":"sd","label":"OWASP dependency check"},{"id":"sn","label":"npm audit"},{"id":"si","label":"Integration tests"},{"id":"sc","label":"Config validation"}]},
  {"id":"i","label":"Image","color":"orange","nodes":[{"id":"ib","label":"Build backend image + scan"},{"id":"if","label":"Build frontend image + scan"}]},
  {"id":"d","label":"Deploy","color":"green","nodes":[{"id":"dg","label":"Manual gate","sub":"DEPLOY_TEST"},{"id":"dt","label":"Deploy to test"},{"id":"dm","label":"Post-deploy smoke"}]}],
 "edges":[{"from":"bb","to":"sd"},{"from":"bf","to":"sn"},{"from":"bs","to":"si"},{"from":"sc","to":"ib"},{"from":"si","to":"if"},{"from":"ib","to":"dg"},{"from":"if","to":"dg"},{"from":"dg","to":"dt"},{"from":"dt","to":"dm"}]}
```

Gates that matter most: tests, contract and migration checks, dependency and image scans, a manual approval before deployment, and a rollback plan that is expand-only for the database.

<!-- pagebreak -->
## 15. Core data model

```diagram
{"type":"er","caption":"Core tables (simplified from the Flyway migrations)",
 "entities":[
  {"name":"tenant","columns":[{"name":"id","type":"VARCHAR(36)","pk":true},{"name":"name","type":"VARCHAR(120)"}]},
  {"name":"recipient","columns":[{"name":"id","type":"VARCHAR(36)","pk":true},{"name":"tenant_id","type":"VARCHAR(36)","fk":true},{"name":"external_ref","type":"VARCHAR(100)"}]},
  {"name":"message_request","columns":[{"name":"id","type":"VARCHAR(36)","pk":true},{"name":"tenant_id","type":"VARCHAR(36)","fk":true},{"name":"recipient_id","type":"VARCHAR(36)","fk":true},{"name":"status","type":"VARCHAR(24)"},{"name":"idempotency_key","type":"VARCHAR(128)"},{"name":"version","type":"BIGINT"}]},
  {"name":"message_attempt","columns":[{"name":"id","type":"VARCHAR(36)","pk":true},{"name":"message_id","type":"VARCHAR(36)","fk":true},{"name":"attempt_no","type":"INT"},{"name":"provider_id","type":"VARCHAR(100)"}]},
  {"name":"delivery_event","columns":[{"name":"id","type":"VARCHAR(36)","pk":true},{"name":"message_id","type":"VARCHAR(36)","fk":true},{"name":"provider_event_id","type":"VARCHAR(100)"}]},
  {"name":"outbox_event","columns":[{"name":"id","type":"VARCHAR(36)","pk":true},{"name":"aggregate_id","type":"VARCHAR(36)"},{"name":"status","type":"VARCHAR(10)"},{"name":"available_at","type":"TIMESTAMP"}]}],
 "relations":[
  {"from":"tenant","to":"recipient","fromCard":"1","toCard":"0..N"},
  {"from":"recipient","to":"message_request","fromCard":"1","toCard":"0..N"},
  {"from":"message_request","to":"message_attempt","fromCard":"1","toCard":"0..N"},
  {"from":"message_request","to":"delivery_event","fromCard":"1","toCard":"0..N"},
  {"from":"message_request","to":"outbox_event","fromCard":"1","toCard":"0..N"}]}
```

Constraints that matter: unique `(tenant_id, idempotency_key)` on the message, unique `(message_id, attempt_no)` on attempts, unique `(provider_id, provider_event_id)` on delivery events, unique `dispatch_id` on `processed_message`.

<!-- pagebreak -->
## 16. Interview answer map

```diagram
{"type":"mindmap","center":"Strong senior answer","caption":"A reusable skeleton for any scenario question",
 "branches":[
  {"label":"Clarify","children":[{"label":"Scope, scale, SLO"},{"label":"What is known versus assumed"}]},
  {"label":"Design","children":[{"label":"Simplest correct design"},{"label":"Failure modes first"},{"label":"Idempotency and ordering"}]},
  {"label":"Evidence","children":[{"label":"Metric, log, trace to confirm"},{"label":"A file or ADR from the project"}]},
  {"label":"Fix safely","children":[{"label":"Smallest reversible change"},{"label":"Feature flag, canary, rollback"}]},
  {"label":"Prevent","children":[{"label":"Test that fails before the fix"},{"label":"Alert and runbook"}]},
  {"label":"Trade-offs","children":[{"label":"Cost, latency, complexity"},{"label":"What I would not claim"}]}]}
```
