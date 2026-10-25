# Delivery Semantics Explainer

## Three guarantees, plainly
| Term | Meaning | Where it applies here |
|---|---|---|
| At-most-once | Never duplicated, may be lost | Not used. A "fire and forget" publish before DB commit would be this (rejected, ADR-0009) |
| At-least-once | Never lost (once persisted), may be duplicated | The transport: outbox to broker, broker to consumer, provider callbacks. Redelivery and replay are expected |
| Exactly-once | Each effect happens once | Impossible end to end across a network boundary to a third party; we do not claim it |
| **Effectively-once (business)** | At-least-once delivery + idempotent processing = one visible effect | What we implement: dedupe at API, consumer, callback, provider ref (ADR-0004) |

## Three different facts about a message
| Fact | Status that records it | Evidence | What it does NOT mean |
|---|---|---|---|
| Platform accepted it | `ACCEPTED/VALIDATED` (202) | committed row + outbox | not sent |
| **Provider accepted it** | `SENT_TO_PROVIDER` | `ProviderOutcome.ACCEPTED` + `provider_message_id` | not delivered; vendor may still bounce or drop |
| **Delivered** | `DELIVERED` | provider-originated receipt callback or reconciliation lookup | for email, delivered to mailbox server is not "read"; OPENED/CLICKED are separate engagement events |
| **Unknown** | `DELIVERY_UNKNOWN` | timeout/ambiguous result, crash, or missing receipt | not failed, not delivered: it is unresolved |

Rule: provider acceptance never implies delivery; absence of a callback is never delivery; a timeout is never failure.

## Where duplicates can still happen (honest list)
1. Provider ignores `idempotencyRef` and we retry after "not received" that was wrong (ADR-0010 residual risk).
2. Operator replays a message that was actually delivered (replay UI shows attempt history and warns for `DELIVERY_UNKNOWN`; replay is audited).
3. Client reuses a different `Idempotency-Key` for the same logical send (client bug).
4. Idempotency key expiry (TTL) then re-use.

## Ordering
No global ordering guarantee. Priority queues reorder by priority; retries reorder in time. Per-recipient ordering is not guaranteed; clients that need ordering must serialize submissions. Callbacks may arrive out of order; the rank rule and `occurred_at` handle this (CONTRACT 3.4).

## Failure windows and their handling
| Crash/failure point | Result | Handling |
|---|---|---|
| After DB commit, before publish | Message sits VALIDATED with outbox NEW | Publisher retries (no loss) |
| After publish, before outbox SENT | Duplicate envelope | Consumer dedupe |
| After consumer CAS, before provider call | Message PROCESSING, no attempt effect | Sweeper to DELIVERY_UNKNOWN is conservative; attempt row STARTED without provider id lets reconciliation conclude "not received" |
| After provider accept, before persist | Provider has it, we do not know | DELIVERY_UNKNOWN then lookup/callback |
| After persist, before ack | Redelivery | CAS/processed_message dedupe |
| Provider callback lost | Receipt never arrives | `receipt-timeout` to DELIVERY_UNKNOWN to lookup |

See `reliability-scenarios.md` and `docs/architecture/reconciliation-flow.md`.
