package com.example.messaging.callback;

import com.example.messaging.domain.DeliveryEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code simulated-sms}: SMPP-style status codes,
 * {@code {"status":"DELIVRD","msg_id":"sm-..","event_id":"evt-1","ts":"2026-10-18T10:15:30Z","ref":"<messageId>:1",
 * "err":"..."}}.
 */
@Component
public class SimulatedSmsPayloadMapper extends AbstractJsonPayloadMapper {

    public static final String PROVIDER_ID = "simulated-sms";

    private static final Map<String, DeliveryEventType> STATUS = new LinkedHashMap<>();

    static {
        STATUS.put("acceptd", DeliveryEventType.PROVIDER_ACCEPTED);
        STATUS.put("sent", DeliveryEventType.SENT);
        STATUS.put("delivrd", DeliveryEventType.DELIVERED);
        STATUS.put("undeliv", DeliveryEventType.FAILED);
        STATUS.put("rejectd", DeliveryEventType.REJECTED);
        STATUS.put("deferrd", DeliveryEventType.DEFERRED);
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
    protected String renderStatus(String lowerCaseKey) {
        return lowerCaseKey.toUpperCase(Locale.ROOT);
    }

    @Override
    public NormalizedEvent map(JsonNode root, String headerEventId, Instant receivedAt) {
        DeliveryEventType type = type(text(root, "status"));
        String messageId = id(text(root, "msg_id"), "msg_id");
        String ref = id(text(root, "ref"), "ref");
        requireLocator(messageId, ref);
        return new NormalizedEvent(PROVIDER_ID, requireEventId(text(root, "event_id"), headerEventId), type, messageId,
                ref, instant(text(root, "ts"), receivedAt), text(root, "err"));
    }

    @Override
    public String render(CallbackSpec spec) {
        ObjectNode n = newNode();
        n.put("status", vendorStatus(spec.type()));
        if (spec.providerMessageId() != null) {
            n.put("msg_id", spec.providerMessageId());
        }
        n.put("event_id", spec.eventId());
        n.put("ts", spec.occurredAt().toString());
        if (spec.idempotencyRef() != null) {
            n.put("ref", spec.idempotencyRef());
        }
        if (spec.reasonCode() != null) {
            n.put("err", spec.reasonCode());
        }
        return write(n);
    }
}
