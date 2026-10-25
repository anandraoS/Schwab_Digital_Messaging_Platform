package com.example.messaging.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.messaging.TestProps;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.provider.ProviderOutcome;
import com.example.messaging.provider.ProviderResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;

/** The diagnostics archive is best effort and redacted: it never breaks the flow and never stores PII. */
class ProviderDiagnosticsStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MessagingMetrics metrics = new MessagingMetrics(registry);
    private final ProviderDiagnosticsStore store = new ProviderDiagnosticsStore(mongo, metrics, TestProps.defaults(),
            new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    private Document archive(String json) {
        when(mongo.getCollection(ProviderDiagnosticsStore.COLLECTION)).thenReturn(collection);
        store.archiveCallback("ref-1", "simulated-email", "evt-1", "msg-1", "accepted",
                json.getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(doc.capture());
        return doc.getValue();
    }

    private double failures() {
        return registry.get(MessagingMetrics.DIAGNOSTICS_WRITE_FAILURES).counter().count();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallbackIsArchivedWithItsContextAndTheRedactedPayload() {
        Document d = archive("{\"event\":\"failed\",\"reason\":\"mailbox alice@example.test full, call +15550100123\","
                + "\"timestamp\":\"2026-10-18T09:59:59Z\",\"meta\":{\"token\":\"abc\",\"nested\":[\"bob@example.test\"]}}");
        assertThat(d.getString("_id")).isEqualTo("ref-1");
        assertThat(d.getString("kind")).isEqualTo("CALLBACK");
        assertThat(d.getString("providerId")).isEqualTo("simulated-email");
        assertThat(d.getString("providerEventId")).isEqualTo("evt-1");
        assertThat(d.getString("messageId")).isEqualTo("msg-1");
        assertThat(d.getString("result")).isEqualTo("accepted");
        assertThat(d.get("createdAt")).isEqualTo(Date.from(NOW));
        Map<String, Object> payload = (Map<String, Object>) d.get("payload");
        assertThat(payload.get("event")).isEqualTo("failed");
        assertThat((String) payload.get("reason")).doesNotContain("alice@example.test").doesNotContain("+15550100123")
                .contains("a***@example.test");
        assertThat(payload.get("timestamp")).isEqualTo("2026-10-18T09:59:59Z"); // timestamps are not digits to hide
        Map<String, Object> meta = (Map<String, Object>) payload.get("meta");
        assertThat(meta.get("token")).isEqualTo("***");
        assertThat(meta.get("nested").toString()).doesNotContain("bob@example.test");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBodyThatIsNotJsonIsStoredAsItsLengthOnlyNeverAsText() {
        Document d = archive("this is alice@example.test speaking");
        Map<String, Object> payload = (Map<String, Object>) d.get("payload");
        assertThat(payload).containsEntry("unparseable", true).containsEntry("length", 35);
        assertThat(d.toJson()).doesNotContain("alice");
    }

    @Test
    void aProviderCallSummaryHasNoContentAndNoAddress() {
        when(mongo.getCollection(ProviderDiagnosticsStore.COLLECTION)).thenReturn(collection);
        store.archiveProviderCall("msg-1", "simulated-sms", 2, ProviderResult.failure(ProviderOutcome.TIMEOUT,
                "PROVIDER_TIMEOUT", "no answer within 200 ms", 200));
        ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
        verify(collection).insertOne(doc.capture());
        Document d = doc.getValue();
        assertThat(d.getString("kind")).isEqualTo("SEND");
        assertThat(d.getInteger("attemptNo")).isEqualTo(2);
        assertThat(d.getString("outcome")).isEqualTo("TIMEOUT");
        assertThat(d.getString("errorCode")).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(d.getLong("latencyMs")).isEqualTo(200L);
        assertThat(d.keySet()).doesNotContain("payload", "to", "body", "subject", "errorMessage");
    }

    @Test
    void aFailingWriteIsSwallowedAndCounted() {
        when(mongo.getCollection(ProviderDiagnosticsStore.COLLECTION)).thenReturn(collection);
        doThrow(new IllegalStateException("mongo is down")).when(collection).insertOne(any(Document.class));
        store.archiveCallback("r", "p", "e", "m", "accepted", "{}".getBytes(StandardCharsets.UTF_8));
        store.archiveProviderCall("m", "p", 1, ProviderResult.accepted("x", 1));
        assertThat(failures()).isEqualTo(2.0);
    }

    @Test
    void aMissingCollectionHandleIsAlsoSwallowed() {
        when(mongo.getCollection(any())).thenThrow(new IllegalStateException("no connection"));
        store.archiveCallback("r", "p", "e", "m", "accepted", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(failures()).isEqualTo(1.0);
    }

    @Test
    void indexCreationFailuresAreCountedAndNeverPropagate() {
        IndexOperations ops = mock(IndexOperations.class);
        when(mongo.indexOps(ProviderDiagnosticsStore.COLLECTION)).thenReturn(ops);
        when(ops.createIndex(any())).thenThrow(new IllegalStateException("not allowed"));
        store.ensureIndexes();
        assertThat(failures()).isEqualTo(1.0);
    }

    @Test
    void indexesIncludeATtlOnCreatedAtSizedFromTheEventRetention() {
        IndexOperations ops = mock(IndexOperations.class);
        when(mongo.indexOps(ProviderDiagnosticsStore.COLLECTION)).thenReturn(ops);
        store.ensureIndexes();
        ArgumentCaptor<org.springframework.data.mongodb.core.index.IndexDefinition> idx =
                ArgumentCaptor.forClass(org.springframework.data.mongodb.core.index.IndexDefinition.class);
        verify(ops, org.mockito.Mockito.times(2)).createIndex(idx.capture());
        Document ttl = idx.getAllValues().get(0).getIndexOptions();
        assertThat(ttl.getString("name")).isEqualTo("ttl_createdAt");
        assertThat(((Number) ttl.get("expireAfterSeconds")).longValue()).isEqualTo(30L * 24 * 3600); // event-days = 30
        assertThat(idx.getAllValues().get(0).getIndexKeys().keySet()).containsExactly("createdAt");
        assertThat(idx.getAllValues().get(1).getIndexKeys().keySet()).containsExactly("messageId");
    }

    @Test
    void readingConvertsIdsAndDatesAndPropagatesFailures() {
        Document stored = new Document("_id", "ref-1").append("kind", "SEND").append("createdAt", Date.from(NOW));
        when(mongo.find(any(Query.class), eq(Document.class), eq(ProviderDiagnosticsStore.COLLECTION)))
                .thenReturn(List.of(stored));
        List<Map<String, Object>> docs = store.findByMessageId("msg-1");
        assertThat(docs).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("id", "ref-1").containsEntry("kind", "SEND")
                    .containsEntry("createdAt", "2026-10-18T10:00:00Z").doesNotContainKey("_id");
        });
        when(mongo.find(any(Query.class), eq(Document.class), eq(ProviderDiagnosticsStore.COLLECTION)))
                .thenThrow(new IllegalStateException("down"));
        assertThatThrownBy(() -> store.findByMessageId("msg-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void referencesAreUnique() {
        assertThat(store.newRef()).isNotEqualTo(store.newRef()).hasSize(36);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactionCopesWithDepthOddKeysAndLists() {
        Map<String, Object> deep = Map.of("a", Map.of("b", Map.of("c", Map.of("d", Map.of("e", Map.of("f", Map.of("g",
                Map.of("h", "alice@example.test"))))))));
        String json = ProviderDiagnosticsStore.redactDeep(deep, 0).toString();
        assertThat(json).doesNotContain("alice");
        Map<String, Object> keys = (Map<String, Object>) ProviderDiagnosticsStore.redactDeep(
                Map.of("a.b", "x", "$set", "y", "email", "z@example.test", "password", "p"), 0);
        assertThat(keys).containsKeys("a_b", "_set").containsEntry("email", "***").containsEntry("password", "***");
        List<Object> list = (List<Object>) ProviderDiagnosticsStore.redactDeep(
                List.of("one", Map.of("phone", "+15550100123"), 5), 0);
        assertThat(list.get(0)).isEqualTo("one");
        assertThat(((Map<String, Object>) list.get(1)).get("phone")).isEqualTo("***");
        assertThat(list.get(2)).isEqualTo(5);
    }
}
