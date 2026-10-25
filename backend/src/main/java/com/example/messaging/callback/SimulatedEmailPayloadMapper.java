package com.example.messaging.callback;

import com.example.messaging.domain.DeliveryEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code simulated-email}: {@code {"event":"delivered","message_id":"em-..","event_id":"evt-1",
 * "timestamp":"2026-10-18T10:15:30Z","meta":{"idempotencyRef":"<messageId>:1"},"reason":"..."}}.
 */
@Component
public class SimulatedEmailPayloadMapper extends AbstractJsonPayloadMapper {

    public static final String PROVIDER_ID = "simulated-email";

    private static final Map<String, DeliveryEventType> STATUS = new LinkedHashMap<>();

    static {
        STATUS.put("queued", DeliveryEventType.PROVIDER_ACCEPTED);
        STATUS.put("sent", DeliveryEventType.SENT);
        STATUS.put("delivered", DeliveryEventType.DELIVERED);
        STATUS.put("hard_bounce", DeliveryEventType.BOUNCED);
        STATUS.put("soft_bounce", DeliveryEventType.DEFERRED);
        STATUS.put("failed", DeliveryEventType.FAILED);
        STATUS.put("rejected", DeliveryEventType.REJECTED);
        STATUS.put("open", DeliveryEventType.OPENED);
        STATUS.put("click", DeliveryEventType.CLICKED);
        STATUS.put("unsubscribe", DeliveryEventType.UNSUBSCRIBED);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    protected Map<String, DeliveryEventType> statusToType() {
        return STATUS;
    }

    @Override
    public NormalizedEvent map(JsonNode root, String headerEventId, Instant receivedAt) {
        DeliveryEventType type = type(text(root, "event"));
        String messageId = id(text(root, "message_id"), "message_id");
        JsonNode meta = root.get("meta");
        String ref = meta != null && meta.isObject() ? id(text(meta, "idempotencyRef"), "idempotencyRef") : null;
        requireLocator(messageId, ref);
        return new NormalizedEvent(PROVIDER_ID, requireEventId(text(root, "event_id"), headerEventId), type, messageId,
                ref, instant(text(root, "timestamp"), receivedAt), text(root, "reason"));
    }

    @Override
    public String render(CallbackSpec spec) {
        ObjectNode n = newNode();
        n.put("event", vendorStatus(spec.type()));
        if (spec.providerMessageId() != null) {
            n.put("message_id", spec.providerMessageId());
        }
        n.put("event_id", spec.eventId());
        n.put("timestamp", spec.occurredAt().toString());
        if (spec.idempotencyRef() != null) {
            n.putObject("meta").put("idempotencyRef", spec.idempotencyRef());
        }
        if (spec.reasonCode() != null) {
            n.put("reason", spec.reasonCode());
        }
        return write(n);
    }
}
