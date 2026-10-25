# 10. Sensitive Data in Logs

Invariants: 9 (no body/address/secret in logs, API, bus, Mongo), 11.

## Flawed code
```java
log.info("Sending SMS to {} body={} provider request={}", recipient.getPhoneE164(), rendered.body(), providerRequest);
...
catch (SignatureException e) {
    log.warn("Invalid callback: body={} signature={} secret={}", rawBody, signatureHeader, secretFor(keyId));
}
```
## Why it is wrong
Phone numbers, message content (may contain account details), signatures (replayable within tolerance) and an HMAC secret are logged: logs are widely accessible, retained, and shipped to third parties. Violates data minimization and secrets rules.

## Corrected version
```java
log.info("Dispatching messageId={} channel={} attemptNo={} provider={}", m.getId(), m.getChannel(), attemptNo, providerId);
...
catch (InvalidSignatureException e) {
    metrics.callbacks("invalid_signature");
    log.warn("Callback rejected providerId={} keyId={} reason={}", providerId, keyId, e.reason());   // reason is an enum, never the secret/body
}
// when structured detail is genuinely needed: diagnostics.store(redaction.redact(providerRequest)) -> Mongo with TTL
```
MDC supplies `correlationId`, `tenant`, `messageId`. Masked addresses (`+1555***0101`) only where an address is useful.

## Rationale
Logs carry identifiers and decisions; content goes nowhere by default; operators needing payload detail use the access-controlled, expiring diagnostics store with redaction.

## Regression tests
- `LoggingPrivacyIT#sendAndFail_logsContainNoPhoneEmailBodyOrSecret`: attach a Logback `ListAppender`, run submit + dispatch + forged callback, assert no line matches `\+1555\d{4,}`, `@example\.test`, the rendered body text, `local-dev-secret-not-for-production`, or `v1=` signature values.
- `RedactionTest#masksKnownKeys` (`secret|password|token|authorization|api[-_]?key|signature`).
- Review checklist item in `checklists/security-review.md`.
