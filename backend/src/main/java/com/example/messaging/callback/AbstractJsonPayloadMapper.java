package com.example.messaging.callback;

import com.example.messaging.domain.DeliveryEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared parsing helpers for the JSON mappers. */
abstract class AbstractJsonPayloadMapper implements ProviderPayloadMapper {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:~=+/-]{1,150}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Vendor status (as received, case-insensitive) to canonical type. */
    protected abstract Map<String, DeliveryEventType> statusToType();

    protected static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isValueNode()) {
            throw new MalformedCallbackException("FIELD_NOT_SCALAR:" + field);
        }
        String s = v.asText();
        return s.isBlank() ? null : s.trim();
    }

    protected static String id(String value, String what) {
        if (value == null) {
            return null;
        }
        if (!SAFE_ID.matcher(value).matches()) {
            throw new MalformedCallbackException("BAD_ID:" + what);
        }
        return value;
    }

    protected DeliveryEventType type(String vendorStatus) {
        if (vendorStatus == null) {
            throw new MalformedCallbackException("MISSING_STATUS");
        }
        DeliveryEventType t = statusToType().get(vendorStatus.toLowerCase(java.util.Locale.ROOT));
        if (t == null) {
            throw new MalformedCallbackException("UNKNOWN_STATUS");
        }
        return t;
    }

    protected String vendorStatus(DeliveryEventType type) {
        for (Map.Entry<String, DeliveryEventType> e : statusToType().entrySet()) {
            if (e.getValue() == type && !e.getKey().contains("|")) {
                return renderStatus(e.getKey());
            }
        }
        throw new IllegalArgumentException(providerId() + " has no vendor status for " + type);
    }

    /** Vendors differ in status casing; subclasses override to restore it. */
    protected String renderStatus(String lowerCaseKey) {
        return lowerCaseKey;
    }

    protected static Instant instant(String iso, Instant fallback) {
        if (iso == null) {
            return fallback;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeException e) {
            throw new MalformedCallbackException("BAD_TIMESTAMP");
        }
    }

    protected static ObjectNode newNode() {
        return JSON.createObjectNode();
    }

    protected static String write(ObjectNode node) {
        return node.toString();
    }

    protected static String requireEventId(String payloadId, String headerId) {
        String id = payloadId != null ? payloadId : headerId;
        if (id == null || id.isBlank()) {
            throw new MalformedCallbackException("MISSING_EVENT_ID");
        }
        return id(id, "event_id");
    }

    protected static void requireLocator(String providerMessageId, String idempotencyRef) {
        if (providerMessageId == null && idempotencyRef == null) {
            throw new MalformedCallbackException("MISSING_MESSAGE_REFERENCE");
        }
    }
}
