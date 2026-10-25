# Runbook: Callback Signature Failures

## Symptoms and alerts
`CallbackSignatureFailures`; `messaging.callbacks{result="invalid_signature"}` spike; delivered statuses stop updating, messages drift to `DELIVERY_UNKNOWN`.

## Two very different causes
| Cause | Signal | Urgency |
|---|---|---|
| Attack / probing | many failures from unknown sources, mixed provider ids, no legit traffic loss | investigate, rate limit at edge |
| Misconfiguration / key rotation | failures for one provider and one `X-Provider-Key-Id` exactly after a change; legitimate receipts stop | high: delivery tracking degraded |

## Diagnose
1. Which provider and key id? Logs (structured, search `invalid_signature` + `providerId`, `keyId`). Raw bodies are not logged by design.
2. Timestamp tolerance: failure reason `timestamp outside tolerance` means clock skew on our hosts or provider; check NTP. Tolerance is 5 min (`messaging.callback.tolerance`).
3. Key rotation: was the new `keyId` added to `messaging.callback.secrets.<keyId>` before the provider switched? (`secrets-management.md`)
4. Body mutation: a proxy or gateway altering the raw body (re-serialization, charset, compression) invalidates HMAC. Verify the HMAC input is raw bytes.
5. Replay vs signature: `replayed` result is a different counter.

## Mitigate
- Misconfiguration: deploy the missing secret/key id; do NOT disable verification or loosen tolerance "temporarily" (never turn off signature checks). 
- Clock skew: fix NTP; tolerance changes require security approval.
- Attack: edge rate limits/IP allow-list for provider ranges; keep verifying.

## Recover
After fix, providers typically retry webhooks (provider-specific). For lost receipts, reconciliation resolves `DELIVERY_UNKNOWN` through `lookup` (`consumer-crash-ambiguous-outcomes.md`).

## Verify
`invalid_signature` rate back to baseline; `accepted` rate resumes; sample message reaches DELIVERED.

## Prevent
Dual-key rotation procedure, rotation tests, alert before cutover, synthetic signed callback check using the simulator (`POST /dev/simulator/callbacks/emit` in local/test).

Proof: `SignatureVerifierTest`, `tests/security` forged-callback script.
