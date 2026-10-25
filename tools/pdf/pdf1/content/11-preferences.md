# 11. Customer Preferences and Suppression

## 11.1 Two different concepts

| Concept | Meaning | Set by | Scope |
|---|---|---|---|
| **Preference** | What a recipient chose: marketing opt-in, per-channel enabled flags, quiet hours, time zone, fallback order | Recipient (through tenant admin or operator tooling) | Per recipient |
| **Suppression** | A hard block on an address and channel: hard bounce, spam complaint, regulatory request, token invalid | Platform automatically or an operator | Per tenant, channel and address hash |

Preferences are soft and reversible; suppression is hard and protects the platform's sender reputation and legal position. Suppression blocks **every category** on that channel, including SECURITY_ALERT.

## 11.2 The data

| Table | Key columns |
|---|---|
| `recipient` | `external_ref` unique per tenant, `email`, `phone_e164`, `push_token`, `locale`, `time_zone`, `deleted`, `version` |
| `recipient_preference` | `marketing_opt_in`, `email_enabled`, `sms_enabled`, `push_enabled`, `quiet_start`, `quiet_end`, `fallback_order`, `version` |
| `suppression` | `tenant_id`, `channel`, `address_hash`, `reason`, `UNIQUE(tenant_id, channel, address_hash)` |

Suppression stores a **hash** of the address, not the address, so the table can block an address without becoming another copy of personal data (`Ids.addressHash`).

All data is synthetic: 60 recipients named "Recipient 001" with `recipient001@example.test` addresses and `+1555010xxxx` numbers, split across two tenants (`tenant-retail`, `tenant-wealth`).

## 11.3 The policy chain

{{diagram:policyChain}}

`PolicyService.decide` is a **pure static function** (recipient, preferences, suppressed flag, channel, category, priority, now) returning a `PolicyDecision` that is rejected, deferred (with an `until` instant) or allowed. Being pure, it is exhaustively testable by table (`PolicyServiceTest`). The order matters:

| Step | Check | Result |
|---|---|---|
| 1 | Recipient missing or `deleted` | `REJECTED(RECIPIENT_DELETED)` |
| 1b | No address for the channel | `REJECTED(NO_ADDRESS)` |
| 2 | Address on the suppression list for the channel | `REJECTED(SUPPRESSED)` for all categories |
| 3 | MARKETING requires an existing preference row with `marketingOptIn=true` **and** the channel enabled | `REJECTED(OPTED_OUT)`. SERVICE with the channel disabled is `REJECTED(CHANNEL_DISABLED)`. TRANSACTIONAL and SECURITY_ALERT ignore both flags; only suppression blocks them |
| 4 | Quiet hours in the recipient's time zone (default 21:00 to 08:00; windows may cross midnight; start equal to end disables them) | MARKETING and LOW or NORMAL SERVICE are **deferred** to the end of the window. An invalid time zone fails closed for MARKETING (`INVALID_PREFERENCES`) and open for the rest |
| 5 | Fallback channels | Each goes through steps 1 to 4 independently |

### Category semantics

| Category | Consent needed | Quiet hours | Typical use |
|---|---|---|---|
| `SECURITY_ALERT` | No (still blocked by suppression) | Always sends | Login codes, fraud alerts |
| `TRANSACTIONAL` | No | Sends unless channel is SMS or PUSH and priority is LOW | Trade confirmations |
| `SERVICE` | Channel `enabled` flag | LOW and NORMAL deferred | Account notices |
| `MARKETING` | Explicit opt-in | Deferred | Newsletters |

> [!IMPORTANT] Defence in depth
> Policy runs **twice**: at accept time (policy evaluated for the instant the message would actually be sent, so a message scheduled for tomorrow morning is not judged by tonight's quiet hours) and again at dispatch time, because preferences can change while a message waits in the queue. A message the recipient opted out of while it was queued is rejected at dispatch (`REJECTED`), never sent.

## 11.4 Managing preferences

| Endpoint | Roles | Behaviour |
|---|---|---|
| `GET /api/v1/recipients` | OPERATOR, TENANT_ADMIN, PLATFORM_ADMIN, SENDER | Search by `q`, paged |
| `GET /api/v1/recipients/{id}/preferences` | same | Returns the preference with its `version`, plus `suppressedChannels` |
| `PUT /api/v1/recipients/{id}/preferences` | TENANT_ADMIN, OPERATOR, PLATFORM_ADMIN | Body includes `version`; a stale value is 409 `CONFLICT_OPTIMISTIC_LOCK` |
| `POST /api/v1/recipients/{id}/suppressions` | TENANT_ADMIN, OPERATOR | Adds a suppression `{channel, reason}` |
| `DELETE /api/v1/recipients/{id}/suppressions/{channel}` | TENANT_ADMIN, OPERATOR | Removes it |

Quiet hours must be sent together (`quietStart` and `quietEnd`, `HH:mm`) with a valid `timeZone`; `fallbackOrder` has at most three channels. The console's `/preferences` page turns the 409 into an explicit "someone else changed this" conflict experience instead of silently overwriting.

### Automatic suppression and opt-out

| Event | Effect |
|---|---|
| Email `hard_bounce` receipt | Suppression of that address on EMAIL |
| Push `token_invalid` receipt | Suppression of that token on PUSH |
| Email `unsubscribe` receipt | `marketingOptIn` cleared |
| Simulator address `...+hardbounce@...` | Hard bounce at send time, suppression added |

## 11.5 Tests that prove it

| Test | Proves |
|---|---|
| `PolicyServiceTest` | The full decision table: deleted, suppressed, opt-out, quiet hours, fallback |
| `PreferenceApiIT` | Optimistic locking, validation, role checks |
| `TenantIsolationIT` | Preferences of another tenant are 404 |
| `ProviderOutcomeIT` | Hard bounce adds suppression; fallback respects consent |
| `RetentionJobTest` | Preference and suppression retention and legal-hold handling |

## Reference material

{{embed:docs/adr/0012-consent-and-suppression-policy.md|title=ADR-0012: Consent and suppression policy}}
