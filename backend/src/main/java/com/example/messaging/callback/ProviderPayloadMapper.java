package com.example.messaging.callback;

import com.example.messaging.domain.DeliveryEventType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Translates one vendor's webhook payload into the canonical {@link NormalizedEvent} (and back, so that simulators
 * and tests can produce vendor-shaped payloads). Registering a bean of this type is all a new provider needs on the
 * callback side (docs/architecture/callbacks-normalization.md). Mappers are pure: no I/O, no logging of payloads.
 */
public interface ProviderPayloadMapper {

    /** Provider id this mapper serves ({@code provider_config.provider_id}). */
    String providerId();

    /**
     * @param root          the parsed JSON object
     * @param headerEventId value of {@code X-Event-Id}, used when the payload carries no event id
     * @param receivedAt    server time, used when the payload carries no timestamp
     * @throws MalformedCallbackException when required fields are missing or the vendor status is unknown
     */
    NormalizedEvent map(JsonNode root, String headerEventId, Instant receivedAt);

    /** Renders a vendor-shaped payload for a canonical event (simulator/test use). */
    String render(CallbackSpec spec);

    /** Canonical event produced by a mapper. */
    record NormalizedEvent(String providerId, String providerEventId, DeliveryEventType type, String providerMessageId,
            String idempotencyRef, Instant occurredAt, String reasonCode) {}

    /** Input of {@link #render}. */
    record CallbackSpec(String eventId, DeliveryEventType type, String providerMessageId, String idempotencyRef,
            Instant occurredAt, String reasonCode) {}
}
