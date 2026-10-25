# Spring DI, Transactions and Hibernate

A reference for senior Spring Boot interviews, mapped to the reference implementation (Spring Boot 3.5.16, Java 21, JPA with Flyway; CONTRACT sections 1, 2 and 4). Examples are illustrative; no claim is made that they were executed.

## Kid-friendly analogies

| Concept | Analogy |
|---|---|
| Dependency injection | A lunch box packed by a parent. The child (your class) does not go shopping for ingredients; the parent (the container) hands over what is needed. You can swap the sandwich in tests without changing the child. |
| Proxy (AOP) | A receptionist in front of a doctor. Every call goes to the receptionist first, who might take notes or start a billing session (a transaction) and then pass you in. If the doctor talks to themselves in the office, the receptionist never hears it. That is the self-invocation pitfall. |
| Transaction | A group project handed in all at once. Either every part is accepted or the whole thing is torn up. |
| Isolation level | How much of other kids' scribbled drafts you can see while you work. |
| N+1 query | Asking the librarian for a list of 100 authors, then walking back 100 times to ask for each author's books, instead of asking once for the authors with their books. |
| Optimistic lock | Two kids edit the same worksheet copy; the teacher stamps a version number, and the second to turn in is told "your copy is out of date, redo it." |
| Pessimistic lock | Taking the only marker and putting a "busy" sign on it while you draw. |

## Dependency injection

Spring's container (an `ApplicationContext`) creates beans from component scanning, `@Configuration` classes and auto-configuration, then wires dependencies.

### Injection styles

| Style | Verdict |
|---|---|
| Constructor injection | Preferred: immutability, required dependencies explicit, easy unit tests without Spring, fails fast |
| Setter injection | For genuinely optional dependencies |
| Field injection (`@Autowired` on a field) | Avoid: hides dependencies, needs reflection in tests |

```java
@Service
public class NotificationService {
    private final MessageRepository messages;
    private final IdempotencyService idempotency;

    public NotificationService(MessageRepository messages, IdempotencyService idempotency) {
        this.messages = messages;
        this.idempotency = idempotency;
    }
}
```

With a single constructor, `@Autowired` is unnecessary. Class and package names such as `NotificationService` and `IdempotencyService` come from CONTRACT section 2.

### Resolving ambiguity

Multiple beans of one type (for example the `EmailProvider`, `SmsProvider`, `PushNotificationProvider` families and simulators, CONTRACT section 2) are resolved by `@Primary`, `@Qualifier`, bean name, or by injecting `List<EmailProvider>` / `Map<String, SmsProvider>` and selecting at runtime, which is how a registry such as `ProviderRegistry` is typically built. Profile-conditional beans (`@Profile("rabbit")` on `RabbitTopologyConfig`, CONTRACT section 2) and `@ConditionalOnProperty` choose implementations such as `messaging.bus.type=memory|rabbit` (CONTRACT section 9).

### Circular dependencies

Spring Boot forbids them by default. They indicate a design problem; resolve by extracting a third component, using events, or injecting lazily only as a last resort.

### Scopes

| Scope | Lifetime | Note |
|---|---|---|
| `singleton` (default) | One per container | Must be thread-safe; keep it stateless |
| `prototype` | New per request for the bean | Injecting into a singleton gives one instance only; use `ObjectProvider` |
| `request`, `session` | Per HTTP request or session | Need a scoped proxy when injected into singletons |

A request-scoped holder (like a `TenantContext`, CONTRACT section 2) is commonly implemented with a `ThreadLocal` or request scope; remember it must be cleared at the end of the request, and it does not propagate to other threads or async executors automatically.

### Bean lifecycle

Instantiate, populate, `BeanPostProcessor` before-init, `@PostConstruct`, `afterPropertiesSet`, after-init (where proxies are created), ready, then `@PreDestroy` on shutdown. `SmartLifecycle` gives ordered start and stop, which fits the graceful-shutdown requirement for consumers (CONTRACT section 6).

### Configuration properties

Prefer `@ConfigurationProperties` records with validation over scattered `@Value`. CONTRACT section 2 names `PropertiesConfig (@ConfigurationProperties records)` and section 9 lists the `messaging.*` keys. Bind from environment variables; keep secrets out of files.

## Proxies and AOP

Features implemented via proxies: `@Transactional`, `@Async`, `@Cacheable`, `@Retryable`, method security (`@PreAuthorize`), Resilience4j annotations.

- Interface-based JDK proxies versus class-based CGLIB proxies (Spring Boot defaults to CGLIB). CGLIB subclasses the class, so `final` classes or methods cannot be advised, and `private` methods are never intercepted.
- Proxies intercept calls that come through the proxy object only.

### Self-invocation pitfall

```java
@Service
class DispatchService {
    public void process(Long id) {
        markProcessing(id);         // direct call: bypasses the proxy
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(Long id) { ... }   // annotation ignored
}
```

Because `process` calls `this.markProcessing`, no new transaction starts. The same applies to `@Async`, `@Cacheable` and security annotations. Fixes, in order of preference: move the method to another bean; use `TransactionTemplate` for programmatic control; as a last resort inject the proxy of itself. Also, `@Transactional` on a non-public method is not applied by default proxy-based AOP, and annotating a `private` method does nothing.

## Transactions

### How `@Transactional` works

The proxy obtains a connection, begins a transaction, binds it to the thread (`TransactionSynchronizationManager`), invokes the method, then commits or rolls back. Default rollback happens on unchecked exceptions (`RuntimeException`, `Error`) only; checked exceptions commit unless `rollbackFor` is set.

```java
@Transactional(rollbackFor = Exception.class, readOnly = false, timeout = 5)
public MessageAcceptedResponse submit(SubmitMessageRequest req) { ... }
```

Place `@Transactional` on the service layer, not controllers or repositories. Keep transactions short: do not call remote providers inside an open database transaction, because it holds a connection and locks across a network call. The messaging design reflects this: submission persists the message, the outbox row and the audit event in one local transaction, and a separate `OutboxPublisher` publishes afterward (CONTRACT section 6).

### Propagation

| Propagation | Behaviour | Typical use |
|---|---|---|
| `REQUIRED` (default) | Join existing or create new | Most service methods |
| `REQUIRES_NEW` | Suspend current, start independent transaction | Audit that must persist even if the caller rolls back; attempt row written `STARTED` before a provider call (CONTRACT section 6) |
| `SUPPORTS` | Join if present, else none | Read helpers |
| `NOT_SUPPORTED` | Suspend transaction, run without | Long non-transactional work |
| `MANDATORY` | Must already be in a transaction, else error | Internal methods that must not run alone |
| `NEVER` | Error if a transaction exists | Rare |
| `NESTED` | Savepoint inside current transaction | Partial rollback (JDBC only) |

`REQUIRES_NEW` takes a second connection; under a small pool with many threads this can deadlock the pool (outer holds one connection and waits for a second that none is free). Size the pool and keep the inner call short.

A subtle trap: with `REQUIRED`, if an inner method throws a runtime exception that the outer catches and swallows, the shared transaction is already marked rollback-only and the outer commit throws `UnexpectedRollbackException`.

### Isolation

| Level | Prevents | Notes |
|---|---|---|
| `READ_UNCOMMITTED` | Nothing | Rarely used |
| `READ_COMMITTED` | Dirty reads | Default in PostgreSQL and Oracle |
| `REPEATABLE_READ` | Plus non-repeatable reads | MySQL default; in PostgreSQL implemented as snapshot isolation |
| `SERIALIZABLE` | Plus phantoms | Retry on serialisation failure |

Isolation is mostly a database concern; see the databases chapter. Prefer a stronger application-level guard (unique constraints, version columns, compare-and-set updates) over raising global isolation.

### Read-only and flush behaviour

`readOnly = true` is a hint: Hibernate skips dirty checking (flush mode manual) and some drivers route to replicas. It does not forbid writes at database level on all setups.

### Transaction and event pitfalls

- Use `@TransactionalEventListener(phase = AFTER_COMMIT)` so side effects only happen when data is committed; but the transaction is already complete, so database work in that listener needs its own `REQUIRES_NEW`.
- `@Transactional` with `@Async` runs in a different thread, hence a different transaction.
- Exceptions thrown during commit (for example a unique constraint violation at flush) surface at the method boundary, not at the line that changed the entity. Handle `DataIntegrityViolationException` at the caller of the transactional method.

### Idempotency with transactions

The submission path claims the idempotency key with a unique constraint on `(tenant_id, idem_key)` (CONTRACT section 4). Concurrent duplicates race on the insert; the loser catches the integrity violation and maps it to the replay or in-progress behaviour (CONTRACT section 3.3 item 6). The database is the arbiter, not an in-memory check.

## Hibernate and JPA

### Entity states

Transient, managed (persistent), detached, removed. Managed entities are tracked by the persistence context (first-level cache) and written on flush through dirty checking. `Open Session in View` (`spring.jpa.open-in-view`) defaults to true in Spring Boot; many teams disable it so lazy loading outside the service layer fails visibly instead of silently issuing queries from the web layer.

### Fetch strategies and N+1

`@ManyToOne` and `@OneToOne` default to EAGER; `@OneToMany` and `@ManyToMany` default to LAZY. Prefer LAZY everywhere and fetch explicitly per use case.

The N+1 problem:

```java
List<MessageRequest> list = repo.findAll();       // 1 query
for (MessageRequest m : list) {
    m.getAttempts().size();                       // N more queries
}
```

Fixes:

| Technique | Example | Caveat |
|---|---|---|
| Fetch join | `select m from MessageRequest m join fetch m.attempts where m.id = :id` | Fetching two collections gives a cartesian product; paginating a fetched collection makes Hibernate paginate in memory |
| `@EntityGraph` | `@EntityGraph(attributePaths = "attempts")` on a repository method | Same cartesian caveat |
| Batch fetching | `@BatchSize(size = 50)` or `hibernate.default_batch_fetch_size` | Turns N+1 into N/50+1; simple and pagination-safe |
| DTO projection | Select only needed columns into a record or interface projection | Best for list screens and read models |
| Two-query pattern | Page of IDs first, then fetch graph for those IDs | Pagination-safe |

CONTRACT section 2 states repositories must avoid N+1 using fetch joins, `@EntityGraph` or projections. For `GET /api/v1/messages` (a paged list, CONTRACT section 5) projections are the natural answer, since the list does not need attempts; for `GET /messages/{id}` the detail needs attempts and delivery events, so an entity graph or explicit fetch is appropriate. Detect N+1 in tests by counting statements (for example with Hibernate statistics or a datasource proxy) and asserting an upper bound.

### Mapping advice

- Implement `equals` and `hashCode` carefully (business key or fixed identifier approach); do not use all fields.
- Avoid bidirectional associations unless needed; always keep both sides in sync via helper methods.
- Use `@Enumerated(EnumType.STRING)` for enums (`MessageStatus`, `Channel` from CONTRACT section 3.2); ordinals break when values are reordered.
- Use `Instant` for timestamps, store UTC.
- UUID string primary keys (`VARCHAR(36)`, CONTRACT section 4) are portable but index-larger than numeric keys.
- Do not expose entities from controllers; return DTO records (CONTRACT section 2, `web/` DTO records). This also prevents leaking fields such as full addresses; the contract requires masked addresses only.

## Locking

### Optimistic locking

A `@Version` column; Hibernate adds `where version = ?` to updates and throws `OptimisticLockException` (Spring wraps it as `ObjectOptimisticLockingFailureException`) when no row matches.

```java
@Entity
class RecipientPreference {
    @Version private Long version;
    ...
}
```

CONTRACT section 4 marks entities with a `version BIGINT` column, and section 5 specifies that `PUT /recipients/{id}/preferences` carries `version` and returns HTTP 409 on a stale write (error code `CONFLICT_OPTIMISTIC_LOCK`). The client reloads, merges and retries. Use optimistic locking when conflicts are rare and the edit spans user think-time.

### Pessimistic locking

`@Lock(LockModeType.PESSIMISTIC_WRITE)` issues `SELECT ... FOR UPDATE`. Add a lock timeout hint and consider `SKIP LOCKED` for work-queue style claiming where the database supports it (both PostgreSQL and Oracle do, but verify H2 behaviour in the test profile). Use it for hot rows with frequent conflicts, and keep the transaction short and lock order consistent to avoid deadlocks.

### Compare-and-set as a third option

CONTRACT section 6 uses a compare-and-set status update (`QUEUED to PROCESSING`; the loser acks and skips) and section 4 uses `UPDATE ... WHERE status=...` for row claims. In JPA:

```java
@Modifying
@Query("update MessageRequest m set m.status = :to where m.id = :id and m.status = :from")
int transition(@Param("id") String id, @Param("from") MessageStatus from, @Param("to") MessageStatus to);
```

If the returned count is 0, another worker won the race. This avoids loading the entity, holding locks across application code, and `@Version` retry loops. Note that bulk JPQL updates bypass the persistence context and do not increment `@Version` unless you do it yourself; use `clearAutomatically = true` when mixing with loaded entities.

<!-- in-project:d06 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/domain/MessageRequest.java`
>
> Two locking styles side by side: optimistic `@Version` where humans edit (preferences, templates), and compare-and-set UPDATEs where machines race (message status, outbox rows, idempotency takeover).
>
> ```java
> @Version
> private Long version;
> // V3__messages.sql:  version BIGINT NOT NULL DEFAULT 0
> // status changes bypass the entity and use compare-and-set UPDATEs, e.g.
> //   NotificationService.transition(id, from, to, reason, actor)  -> returns false when the race is lost
> ```


## Batching

Writing many rows one-by-one is slow. Hibernate settings:

```text
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
```

Caveats: `GenerationType.IDENTITY` disables JDBC insert batching in Hibernate. Assigned UUIDs (the contract's `VARCHAR(36)` ids) or sequences with a pooled optimiser keep batching available. Flush and clear the persistence context periodically in large loops to bound memory. For the bulk job, Spring Batch chunk-oriented processing commits per chunk (chunk size 500, CONTRACT section 7), which gives a natural batching and restart boundary; the writer creates message rows through `NotificationService.submitInternal` inside a transaction per chunk, with deterministic idempotency keys so a restarted chunk does not duplicate.

## Testing slices

| Annotation | Loads | Use |
|---|---|---|
| Plain JUnit and Mockito | No Spring | Domain logic, `MessageStateMachine`, `RetryPolicy` (pure Java, CONTRACT section 2) |
| `@WebMvcTest` | MVC layer, security filters if imported, mocked services | Controller contracts, validation, error mapping through `GlobalExceptionHandler` |
| `@DataJpaTest` | JPA, repositories, embedded DB (replaces datasource by default) | Queries, mappings, N+1 assertions; add `@AutoConfigureTestDatabase(replace = NONE)` for a real DB |
| `@JsonTest` | Jackson only | DTO serialisation |
| `@SpringBootTest` | Full context | End-to-end paths; CONTRACT section 2 describes an `AbstractIntegrationTest` base with profile `test` |
| Testcontainers | Real PostgreSQL, RabbitMQ, Redis, Mongo | CONTRACT section 2 places these in `*ContainerIT` and Maven profile `-Pcontainers`, not executed in the authoring environment |

An important caveat for the interview: H2 in `MODE=PostgreSQL` (CONTRACT section 1) is a compatibility approximation. Differences in locking, `SKIP LOCKED`, isolation and SQL dialect mean container tests against real PostgreSQL are the authoritative check. A `@Transactional` test method rolls back by default, which can hide commit-time behaviour (constraint checks at flush, `AFTER_COMMIT` listeners); use `@Commit` or `TestTransaction` when you need to observe a real commit.

## Interview scenario: why did my transaction not roll back?

Checklist: Was the method invoked through the proxy (not self-invocation)? Is it `public`? Was the exception checked (needs `rollbackFor`)? Was the exception caught inside and swallowed? Is there an `@Async` or new thread boundary? Is the code running in a different transaction because of `REQUIRES_NEW`? Is `TransactionManager` bound to the right datasource when several exist?

## Key takeaways

- Prefer constructor injection; singletons must be thread-safe; scoped beans need proxies or `ObjectProvider` when injected into singletons.
- Proxy-based features (`@Transactional`, `@Async`, `@Cacheable`) do not apply to self-invocation, private methods or final classes.
- Transactions roll back on unchecked exceptions by default; keep them short and never hold one across a remote call.
- `REQUIRES_NEW` suspends the outer transaction and needs a second connection; mind pool size.
- Default to LAZY; cure N+1 with projections, `@EntityGraph`, fetch joins or batch fetching, and verify with statement counts.
- Use `@Version` optimistic locking for user edits (409 on stale write), pessimistic locks for hot rows, and compare-and-set updates for claim-style transitions.
- `IDENTITY` ids disable insert batching; configure batch size and flush and clear in loops.
- Use the narrowest test slice that proves the point, and treat H2 compatibility mode as an approximation of PostgreSQL.
