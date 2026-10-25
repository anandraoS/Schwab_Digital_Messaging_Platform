package com.example.messaging.diagnostics;

import com.example.messaging.common.Ids;
import com.example.messaging.common.Redaction;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.provider.ProviderResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Best-effort archive of raw provider callbacks and provider call summaries in MongoDB
 * ({@code provider_diagnostics}). It exists for operators ("what exactly did the provider tell us?") and never takes
 * part in the message flow: every write failure is swallowed and counted in
 * {@code messaging.diagnostics.write.failures}. Payloads are redacted before they are stored (addresses, long
 * numbers, tokens), documents expire through a TTL index, and message content is never stored at all.
 */
@Component
public class ProviderDiagnosticsStore {

    public static final String COLLECTION = "provider_diagnostics";
    private static final Logger log = LoggerFactory.getLogger(ProviderDiagnosticsStore.class);
    private static final int MAX_DEPTH = 6;
    private static final java.util.regex.Pattern ISO_INSTANT =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z");

    private final MongoTemplate mongo;
    private final MessagingMetrics metrics;
    private final MessagingProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    public ProviderDiagnosticsStore(MongoTemplate mongo, MessagingMetrics metrics, MessagingProperties props,
            ObjectMapper json, Clock clock) {
        this.mongo = mongo;
        this.metrics = metrics;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    @PostConstruct
    void ensureIndexes() {
        try {
            mongo.indexOps(COLLECTION).createIndex(new Index().on("createdAt", Sort.Direction.ASC)
                    .expire(Duration.ofDays(Math.max(1, props.retention().eventDays()))).named("ttl_createdAt"));
            mongo.indexOps(COLLECTION).createIndex(new Index().on("messageId", Sort.Direction.ASC).named("idx_messageId"));
        } catch (RuntimeException e) {
            metrics.diagnosticsWriteFailure();
            log.warn("Could not create provider_diagnostics indexes: {}", e.getClass().getSimpleName());
        }
    }

    /** Reference stored on the delivery event ({@code raw_ref}) before the document itself is written. */
    public String newRef() {
        return Ids.newId();
    }

    /** Archives a callback body (redacted). Never throws. */
    public void archiveCallback(String ref, String providerId, String providerEventId, String messageId, String result,
            byte[] rawBody) {
        Document doc = base(ref, "CALLBACK", providerId, messageId);
        doc.put("providerEventId", providerEventId);
        doc.put("result", result);
        doc.put("payload", redactedPayload(rawBody));
        write(doc);
    }

    /** Archives the outcome of one provider call (no content, no address). Never throws. */
    public void archiveProviderCall(String messageId, String providerId, int attemptNo, ProviderResult result) {
        Document doc = base(Ids.newId(), "SEND", providerId, messageId);
        doc.put("attemptNo", attemptNo);
        doc.put("outcome", result.outcome().name());
        doc.put("errorCode", result.errorCode());
        doc.put("providerMessageId", result.providerMessageId());
        doc.put("latencyMs", result.latencyMs());
        write(doc);
    }

    /** @throws RuntimeException when MongoDB cannot be read (callers surface 503) */
    public List<Map<String, Object>> findByMessageId(String messageId) {
        Query q = Query.query(Criteria.where("messageId").is(messageId)).with(Sort.by(Sort.Direction.ASC, "createdAt"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : mongo.find(q, Document.class, COLLECTION)) {
            Map<String, Object> m = new LinkedHashMap<>();
            d.forEach((k, v) -> m.put(k.equals("_id") ? "id" : k, v instanceof Date date ? date.toInstant().toString() : v));
            out.add(m);
        }
        return out;
    }

    private Document base(String id, String kind, String providerId, String messageId) {
        Document d = new Document();
        d.put("_id", id);
        d.put("kind", kind);
        d.put("providerId", providerId);
        d.put("messageId", messageId);
        d.put("createdAt", Date.from(clock.instant()));
        return d;
    }

    private void write(Document doc) {
        try {
            mongo.getCollection(COLLECTION).insertOne(doc);
        } catch (RuntimeException e) {
            metrics.diagnosticsWriteFailure();
            log.warn("Provider diagnostics write failed: {}", e.getClass().getSimpleName());
        }
    }

    private Object redactedPayload(byte[] raw) {
        try {
            Map<String, Object> parsed = json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
            return redactDeep(parsed, 0);
        } catch (Exception e) {
            // not JSON: keep only the size, never the text
            return Map.of("unparseable", true, "length", raw == null ? 0 : raw.length);
        }
    }

    @SuppressWarnings("unchecked")
    static Object redactDeep(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return "***";
        }
        if (value instanceof Map<?, ?> m) {
            // keys that name secrets are masked here; string values are pattern-redacted below, one by one
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k.replace('.', '_').replace("$", "_"),
                    Redaction.isSensitiveKey(k) ? Redaction.MASK : redactDeep(v, depth + 1)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(i -> out.add(redactDeep(i, depth + 1)));
            return out;
        }
        if (value instanceof String s) {
            return ISO_INSTANT.matcher(s).matches() ? s : Redaction.redact(s);
        }
        return value;
    }
}
