# 6. Message Lifecycle and State Machine

## 6.1 Why an explicit state machine

A notification is not "sent" or "not sent". Between the API call and the customer's inbox there are at least four different facts, and conflating them is the root of most messaging incidents.

| Fact | Status that records it | What it does NOT mean |
|---|---|---|
| The platform accepted the request | `ACCEPTED`, `VALIDATED` | Not sent |
| The **provider** accepted it | `SENT_TO_PROVIDER` | Not delivered; the vendor can still bounce or drop it |
| It was **delivered** | `DELIVERED` | For email, delivered to the mailbox server, not "read" |
| We **do not know** | `DELIVERY_UNKNOWN` | Not failed and not delivered: unresolved |

Three rules follow, and every part of the code obeys them: provider acceptance never implies delivery; the absence of a callback is never delivery; a timeout is never failure.

An analogy for children: a parcel has *"the shop packed it"*, *"the courier picked it up"*, *"it arrived at the door"* and *"the courier lost track of it"*. Four different sentences, and a good tracking page never mixes them up.

## 6.2 The state machine

The machine is a static `EnumMap<MessageStatus, Set<MessageStatus>>` in `com.example.messaging.domain.MessageStateMachine`:

```java
TABLE.put(ACCEPTED, EnumSet.of(VALIDATED, REJECTED));
TABLE.put(VALIDATED, EnumSet.of(QUEUED, CANCELLED, REJECTED));
TABLE.put(QUEUED, EnumSet.of(PROCESSING, CANCELLED));
TABLE.put(PROCESSING, EnumSet.of(SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED));
TABLE.put(RETRY_PENDING, EnumSet.of(QUEUED, DEAD_LETTERED, CANCELLED));
TABLE.put(SENT_TO_PROVIDER, EnumSet.of(DELIVERED, FAILED, DELIVERY_UNKNOWN));
TABLE.put(DELIVERY_UNKNOWN, EnumSet.of(DELIVERED, FAILED));
TABLE.put(FAILED, EnumSet.of(DEAD_LETTERED, QUEUED));
TABLE.put(DEAD_LETTERED, EnumSet.of(QUEUED));
```

{{diagram:lifecycle}}

## 6.3 Transition matrix

| From | Allowed to | Actor | Trigger |
|---|---|---|---|
| ACCEPTED | VALIDATED, REJECTED | Submission service | Synchronous, inside the submit transaction |
| VALIDATED | QUEUED, CANCELLED, REJECTED | `OutboxPublisher`, API cancel, dispatch-time policy | Outbox row due; cancel call; policy decided at dispatch |
| QUEUED | PROCESSING, CANCELLED | `DispatchConsumer`, API cancel | Claim; cancel call |
| PROCESSING | SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED | `DispatchConsumer` | Provider outcome |
| RETRY_PENDING | QUEUED, DEAD_LETTERED, CANCELLED | Retry scheduler, operator | Backoff elapsed; attempts exhausted |
| SENT_TO_PROVIDER | DELIVERED, FAILED, DELIVERY_UNKNOWN | Callback processor, sweeper | Receipt; no receipt within `receipt-timeout` |
| DELIVERY_UNKNOWN | DELIVERED, FAILED | Callback processor, `ReconciliationService` | Late callback or lookup evidence |
| FAILED | DEAD_LETTERED, QUEUED | Pipeline; operator requeue only for QUEUED | Fallback exhausted; operator action |
| DEAD_LETTERED | QUEUED | Operator replay only | `POST /dead-letters/{id}/replay` |
| DELIVERED, REJECTED, CANCELLED | none | terminal | |

Cancellation is permitted only from `VALIDATED`, `QUEUED` and `RETRY_PENDING` (`MessageStateMachine.canCancel`). `FAILED` is terminal unless an operator requeues; `DEAD_LETTERED` is terminal unless an operator replays (`isOperatorOnly`).

## 6.4 Multi-step outcomes use legal single steps

Real outcomes sometimes need two moves, and the table has no shortcut for them (there is no `QUEUED -> REJECTED`, for example). The implementation never bypasses the table; it chains individually legal transitions in one transaction (DEVIATIONS, stage 2, item 4):

| Outcome | Chain |
|---|---|
| Fallback after `PERMANENT_FAILURE` | `PROCESSING -> FAILED -> QUEUED` |
| Fallback or dead letter after exhausted retries | `PROCESSING -> RETRY_PENDING -> QUEUED` or `-> DEAD_LETTERED` |
| Reconciliation "not received" | `DELIVERY_UNKNOWN -> FAILED -> QUEUED` |
| Mirroring a poison message | `QUEUED -> PROCESSING -> FAILED -> DEAD_LETTERED` |
| Expiry or dispatch-time rejection | claim first (`QUEUED -> PROCESSING`), then `PROCESSING -> FAILED(EXPIRED)` or `-> REJECTED` |

Quiet hours met at dispatch time defer through the **outbox** (a new outbox row with `available_at` at the end of the window; the message stays `QUEUED`); there is deliberately no `QUEUED -> VALIDATED` transition.

## 6.5 Receipts and the monotonic rank rule

Provider receipts can arrive in any order and more than once. `MessageStateMachine.resolveReceipt(current, type)` decides what a receipt does:

```java
public static ReceiptResolution resolveReceipt(MessageStatus current, DeliveryEventType type) {
    if (type.rank() < 3) return ReceiptResolution.IGNORE_LOWER_RANK;
    if (current == DELIVERED) {
        return type == DeliveryEventType.DELIVERED
                ? ReceiptResolution.IGNORE_LOWER_RANK : ReceiptResolution.CONFLICT;
    }
    boolean receivable = current == PROCESSING || current == SENT_TO_PROVIDER || current == DELIVERY_UNKNOWN;
    ...
}
```

| Receipt | Rank | Effect |
|---|---|---|
| `PROVIDER_ACCEPTED`, `SENT`, `DEFERRED` | 1 | Never downgrades; recorded, ignored once the outcome is known |
| `DELIVERED` | 3 | Applies from `PROCESSING`, `SENT_TO_PROVIDER`, `DELIVERY_UNKNOWN` |
| `BOUNCED`, `FAILED`, `REJECTED` | 3 | To `FAILED` (a hard bounce also adds a suppression) |
| `OPENED`, `CLICKED`, `UNSUBSCRIBED` | engagement | Recorded; unsubscribe clears marketing opt-in |
| Opposite terminal after the first | | `CONFLICT`: the first terminal wins, audit `DELIVERY_EVENT_CONFLICT` |

`canTransitionViaReceipt` additionally permits `PROCESSING -> DELIVERED` and `PROCESSING -> FAILED`: a receipt may overtake the consumer's own persistence of `SENT_TO_PROVIDER`.

## 6.6 Cancel versus dispatch

{{diagram:cancelRace}}

## 6.7 Compare-and-set ownership

The lifecycle's integrity does not rely on locks held across calls. Each transition is a single conditional update, which the database serialises:

```sql
UPDATE message_request SET status = :to, ... WHERE id = :id AND status = :from
```

`NotificationService.transition(messageId, from, to, reason, actor)` returns `true` only for the caller whose update matched a row. Callers that lose back off: the consumer acknowledges and skips, the API answers 409 `ILLEGAL_STATE_TRANSITION`, the publisher moves on.

## 6.8 The lifecycle as an operator sees it

The message detail page of the console renders the merged history (`GET /api/v1/messages/{id}/history`) as a lifecycle timeline next to the attempt list. The screenshot below was recorded from the running local backend (synthetic data; the page never shows message content, and the address is masked): the message was accepted, published by the outbox (`VALIDATED -> QUEUED`), claimed by the dispatch consumer (`QUEUED -> PROCESSING`), attempted once through `simulated-email` (outcome `ACCEPTED`) and left in `SENT_TO_PROVIDER`: provider acceptance, not delivery, with no delivery events yet.

![Message detail with lifecycle timeline and attempts. Source: docs/validation/screenshots/05-message-detail.png](docs/validation/screenshots/05-message-detail.png)

## 6.9 Tests that prove it

| Test | Proves |
|---|---|
| `MessageStateMachineTest` | The full 12 by 12 transition matrix, cancellable and requeueable sets, receipt resolution |
| `CancelRaceIT` | Exactly one of cancel and dispatch wins |
| `ConcurrencyIT` | Concurrent claims of one message produce one owner |
| `MessageLifecycleIT` | Legal transitions through the service, history timeline contents |
| `FailureRecoveryIT#scenario03_*` | Duplicate and out-of-order callbacks apply monotonically |

## Reference material

{{embed:docs/adr/0003-message-lifecycle-state-machine.md|title=ADR-0003: Message lifecycle state machine}}

{{embed:docs/architecture/delivery-semantics.md|title=Delivery semantics (failure windows)|only=Where duplicates can still happen;Ordering;Failure windows}}
