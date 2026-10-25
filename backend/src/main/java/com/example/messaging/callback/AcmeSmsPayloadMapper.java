package com.example.messaging.callback;

import com.example.messaging.domain.DeliveryEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code acme-sms}: a deliberately different, minimal shape: {@code {"ref":"acme-..","state":"ok","ts":1792318530,
 * "id":"evt-1"}} ({@code ts} is epoch seconds, {@code ref} is Acme's own message id, no idempotency echo). This
 * class plus a bean registration is everything the "new provider" example needs on the callback side.
 */
@Component
public class AcmeSmsPayloadMapper extends AbstractJsonPayloadMapper {

    public static final String PROVIDER_ID = "acme-sms";

    private static final Map<String, DeliveryEventType> STATUS = new LinkedHashMap<>();

    static {
        STATUS.put("queued", DeliveryEventType.PROVIDER_ACCEPTED);
        STATUS.put("sent", DeliveryEventType.SENT);
        STATUS.put("ok", DeliveryEventType.DELIVERED);
        STATUS.put("fail", DeliveryEventType.FAILED);
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
        DeliveryEventType type = type(text(root, "state"));
        String ref = id(text(root, "ref"), "ref");
        if (ref == null) {
            throw new MalformedCallbackException("MISSING_MESSAGE_REFERENCE");
        }
        Instant occurred = receivedAt;
        JsonNode ts = root.get("ts");
        if (ts != null && !ts.isNull()) {
            if (!ts.isNumber()) {
                throw new MalformedCallbackException("BAD_TIMESTAMP");
            }
            occurred = Instant.ofEpochSecond(ts.asLong());
        }
        return new NormalizedEvent(PROVIDER_ID, requireEventId(text(root, "id"), headerEventId), type, ref, null,
                occurred, text(root, "code"));
    }

    @Override
    public String render(CallbackSpec spec) {
        ObjectNode n = newNode();
        n.put("ref", spec.providerMessageId());
        n.put("state", vendorStatus(spec.type()));
        n.put("ts", spec.occurredAt().getEpochSecond());
        n.put("id", spec.eventId());
        if (spec.reasonCode() != null) {
            n.put("code", spec.reasonCode());
        }
        return write(n);
    }
}
