package com.example.messaging.callback;

import com.example.messaging.domain.DeliveryEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code simulated-push}: {@code {"type":"delivered","notification_id":"pn-..","id":"evt-1",
 * "time":"2026-10-18T10:15:30Z","ref":"<messageId>:1","error":"..."}}. {@code token_invalid} maps to FAILED with
 * reason {@code TOKEN_INVALID}.
 */
@Component
public class SimulatedPushPayloadMapper extends AbstractJsonPayloadMapper {

    public static final String PROVIDER_ID = "simulated-push";
    public static final String TOKEN_INVALID = "TOKEN_INVALID";

    private static final Map<String, DeliveryEventType> STATUS = new LinkedHashMap<>();

    static {
        STATUS.put("accepted", DeliveryEventType.PROVIDER_ACCEPTED);
        STATUS.put("sent", DeliveryEventType.SENT);
        STATUS.put("delivered", DeliveryEventType.DELIVERED);
        STATUS.put("token_invalid", DeliveryEventType.FAILED);
        STATUS.put("failed", DeliveryEventType.REJECTED);
        STATUS.put("opened", DeliveryEventType.OPENED);
        STATUS.put("clicked", DeliveryEventType.CLICKED);
        STATUS.put("deferred", DeliveryEventType.DEFERRED);
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
        String vendor = text(root, "type");
        DeliveryEventType type = type(vendor);
        String reason = text(root, "error");
        if ("token_invalid".equalsIgnoreCase(vendor)) {
            reason = TOKEN_INVALID;
        }
        String messageId = id(text(root, "notification_id"), "notification_id");
        String ref = id(text(root, "ref"), "ref");
        requireLocator(messageId, ref);
        return new NormalizedEvent(PROVIDER_ID, requireEventId(text(root, "id"), headerEventId), type, messageId, ref,
                instant(text(root, "time"), receivedAt), reason);
    }

    @Override
    public String render(CallbackSpec spec) {
        ObjectNode n = newNode();
        boolean tokenInvalid = spec.type() == DeliveryEventType.FAILED && TOKEN_INVALID.equals(spec.reasonCode());
        n.put("type", tokenInvalid ? "token_invalid" : vendorStatus(spec.type()));
        if (spec.providerMessageId() != null) {
            n.put("notification_id", spec.providerMessageId());
        }
        n.put("id", spec.eventId());
        n.put("time", spec.occurredAt().toString());
        if (spec.idempotencyRef() != null) {
            n.put("ref", spec.idempotencyRef());
        }
        if (spec.reasonCode() != null && !tokenInvalid) {
            n.put("error", spec.reasonCode());
        }
        return write(n);
    }
}
