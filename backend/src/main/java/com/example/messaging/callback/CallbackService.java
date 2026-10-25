package com.example.messaging.callback;

import com.example.messaging.callback.DeliveryEventProcessor.Result;
import com.example.messaging.callback.ProviderPayloadMapper.NormalizedEvent;
import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.diagnostics.ProviderDiagnosticsStore;
import com.example.messaging.domain.ProviderConfig;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.repository.ProviderConfigRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The whole inbound-callback pipeline behind {@code POST /api/v1/callbacks/{providerId}} (docs/architecture/
 * callback-flow.md): authenticate (key id bound to the provider, HMAC over the raw body, timestamp tolerance),
 * replay guard, normalise, apply, archive. The HTTP controller and the in-JVM simulator both call
 * {@link #handle}, so simulated callbacks take exactly the production path including the signature.
 *
 * <p>Response policy: 2xx for everything authentic (202 accepted/duplicate/ignored, 200 replayed) so providers stop
 * retrying; 401 for any signature problem (also for an unknown provider, so providers cannot be enumerated); 400 for
 * authentic but unintelligible payloads or a missing {@code X-Event-Id}.
 */
@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);
    private static final Pattern SAFE_PROVIDER = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:~=+/-]{1,150}");

    /** HTTP-level answer: {@code 202} for processed events, {@code 200} for replays. */
    public record Response(int status, String result, String eventId) {}

    private final ProviderConfigRepository configs;
    private final SignatureVerifier verifier;
    private final ReplayGuard replayGuard;
    private final EventNormalizer normalizer;
    private final DeliveryEventProcessor processor;
    private final ProviderDiagnosticsStore diagnostics;
    private final MessagingMetrics metrics;
    private final Clock clock;

    public CallbackService(ProviderConfigRepository configs, SignatureVerifier verifier, ReplayGuard replayGuard,
            EventNormalizer normalizer, DeliveryEventProcessor processor, ProviderDiagnosticsStore diagnostics,
            MessagingMetrics metrics, Clock clock) {
        this.configs = configs;
        this.verifier = verifier;
        this.replayGuard = replayGuard;
        this.normalizer = normalizer;
        this.processor = processor;
        this.diagnostics = diagnostics;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Response handle(String providerId, String keyId, String signatureHeader, String eventIdHeader,
            byte[] rawBody) {
        authenticate(providerId, keyId, signatureHeader, rawBody);

        if (eventIdHeader == null || !EVENT_ID.matcher(eventIdHeader).matches()) {
            metrics.callback("malformed");
            throw new ApiException.ValidationException("X-Event-Id", "header is required (1-150 safe characters)");
        }
        String guardKey = ReplayGuard.key(providerId, eventIdHeader);
        if (replayGuard.isReplay(guardKey)) {
            metrics.callback("replayed");
            log.info("Callback replay ignored for provider {}", providerId);
            return new Response(200, "replayed", eventIdHeader);
        }

        NormalizedEvent event;
        try {
            event = normalizer.normalize(providerId, rawBody, eventIdHeader, clock.instant());
        } catch (MalformedCallbackException e) {
            metrics.callback("malformed");
            log.warn("Callback payload rejected for provider {}: {}", providerId, e.getMessage());
            diagnostics.archiveCallback(diagnostics.newRef(), providerId, eventIdHeader, null,
                    "malformed:" + e.getMessage(), rawBody);
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Callback payload could not be understood");
        }

        String rawRef = diagnostics.newRef();
        Result result = processor.apply(event, rawRef);
        if (!"unknown_message".equals(result.result())) {
            replayGuard.markSeen(guardKey); // an unknown message may legitimately be retried after our commit
        }
        diagnostics.archiveCallback(rawRef, providerId, event.providerEventId(), result.messageId(), result.result(),
                rawBody);
        metrics.callback(result.result());
        return new Response(202, result.result(), event.providerEventId());
    }

    private void authenticate(String providerId, String keyId, String signatureHeader, byte[] rawBody) {
        String reason;
        Optional<ProviderConfig> cfg = SAFE_PROVIDER.matcher(providerId == null ? "" : providerId).matches()
                ? configs.findByProviderId(providerId) : Optional.empty();
        if (cfg.isEmpty()) {
            reason = "unknown_provider";
        } else {
            String ref = cfg.get().getHmacKeyRef();
            Set<String> allowed = ref == null ? Set.of() : Set.of(ref, ref + "-next");
            if (keyId == null || !allowed.contains(keyId)) {
                reason = "key_not_bound_to_provider";
            } else {
                Optional<String> secret = verifier.secretFor(keyId);
                if (secret.isEmpty()) {
                    log.error("No callback secret configured for key id {}", keyId);
                    reason = "no_secret_configured";
                } else {
                    SignatureVerifier.Result r = verifier.verify(signatureHeader, secret.get(), rawBody);
                    if (r == SignatureVerifier.Result.VALID) {
                        return;
                    }
                    reason = r.name().toLowerCase();
                }
            }
        }
        metrics.callback("invalid_signature");
        log.warn("Callback rejected: provider={} reason={}",
                SAFE_PROVIDER.matcher(providerId == null ? "" : providerId).matches() ? providerId : "?", reason);
        throw new ApiException(ErrorCode.INVALID_SIGNATURE, "Invalid signature");
    }
}
