package com.example.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import com.example.messaging.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * The committed OpenAPI file is the reviewed contract; springdoc output is what the code really serves. They must
 * agree: every committed path that this module implements is served, and every served path is documented.
 */
class OpenApiContractIT extends AbstractIntegrationTest {

    /** The whole committed spec is enforced: every module (including bulk jobs) is served. */
    private static final List<String> EXEMPT_PREFIXES = List.of();
    /** Development-only endpoints are hidden from springdoc on purpose. */
    private static final List<String> DEV_PREFIXES = List.of("/dev/");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");

    /** Every endpoint of CONTRACT section 5. */
    private static final List<String> CONTRACT_ENDPOINTS = List.of(
            "POST /api/v1/messages", "GET /api/v1/messages/{}", "GET /api/v1/messages", "GET /api/v1/messages/{}/status",
            "GET /api/v1/messages/{}/history", "POST /api/v1/messages/{}/cancel", "POST /api/v1/messages/{}/requeue",
            "GET /api/v1/dead-letters", "POST /api/v1/dead-letters/{}/replay", "POST /api/v1/dead-letters/{}/discard",
            "GET /api/v1/failed-messages", "GET /api/v1/delivery-events", "GET /api/v1/audit-events",
            "GET /api/v1/templates", "POST /api/v1/templates", "GET /api/v1/templates/{}",
            "POST /api/v1/templates/{}/versions", "PUT /api/v1/templates/{}/versions/{}",
            "POST /api/v1/templates/{}/versions/{}/submit", "POST /api/v1/templates/{}/versions/{}/approve",
            "POST /api/v1/templates/{}/versions/{}/reject", "POST /api/v1/templates/{}/versions/{}/retire",
            "POST /api/v1/templates/preview", "GET /api/v1/templates/{}/history",
            "GET /api/v1/recipients", "GET /api/v1/recipients/{}/preferences", "PUT /api/v1/recipients/{}/preferences",
            "POST /api/v1/recipients/{}/suppressions", "DELETE /api/v1/recipients/{}/suppressions/{}",
            "GET /api/v1/providers", "PUT /api/v1/providers/{}/config", "PUT /api/v1/providers/{}/fault-mode",
            "GET /api/v1/ops/queues", "GET /api/v1/ops/summary", "POST /api/v1/ops/consumers/pause",
            "POST /api/v1/ops/consumers/resume",
            "POST /api/v1/bulk-jobs", "POST /api/v1/bulk-jobs/generate-synthetic", "GET /api/v1/bulk-jobs",
            "GET /api/v1/bulk-jobs/{}", "GET /api/v1/bulk-jobs/{}/failures", "POST /api/v1/bulk-jobs/{}/stop",
            "POST /api/v1/bulk-jobs/{}/restart",
            "GET /api/v1/diagnostics/messages/{}", "POST /api/v1/callbacks/{}",
            "POST /dev/simulator/callbacks/emit", "GET /api/v1/me", "POST /dev/token");

    @SuppressWarnings("unchecked")
    private Map<String, Object> committedSpec() throws Exception {
        try (InputStream in = new ClassPathResource("openapi/messaging-api-v1.yaml").getInputStream()) {
            return new Yaml().load(in);
        }
    }

    private static String normalise(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    @SuppressWarnings("unchecked")
    private Set<String> committedOperations() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) committedSpec().get("paths");
        Set<String> ops = new LinkedHashSet<>();
        paths.forEach((p, item) -> ((Map<String, Object>) item).keySet().stream().filter(METHODS::contains)
                .forEach(m -> ops.add(m.toUpperCase() + " " + normalise(p))));
        return ops;
    }

    private Set<String> servedOperations() throws Exception {
        JsonNode doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        Set<String> ops = new LinkedHashSet<>();
        doc.get("paths").fields().forEachRemaining(e -> e.getValue().fieldNames().forEachRemaining(m -> {
            if (METHODS.contains(m)) {
                ops.add(m.toUpperCase() + " " + normalise(e.getKey()));
            }
        }));
        return ops;
    }

    private static boolean isPending(String op) {
        String path = op.substring(op.indexOf(' ') + 1);
        return EXEMPT_PREFIXES.stream().anyMatch(path::startsWith) || DEV_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Test
    void everyCommittedPathThatThisModuleImplementsIsServedBySpringdoc() throws Exception {
        Set<String> served = servedOperations();
        List<String> missing = new ArrayList<>();
        for (String op : committedOperations()) {
            if (!isPending(op) && !served.contains(op)) {
                missing.add(op);
            }
        }
        assertThat(missing).as("committed operations that the running application does not serve").isEmpty();
        assertThat(served).as("sanity: springdoc is describing real endpoints").hasSizeGreaterThan(25);
    }

    @Test
    void everyServedPathIsDocumentedInTheCommittedSpec() throws Exception {
        Set<String> committed = committedOperations();
        List<String> undocumented = new ArrayList<>();
        for (String op : servedOperations()) {
            if (!committed.contains(op) && !op.contains("/v3/api-docs") && !op.contains("/swagger")) {
                undocumented.add(op);
            }
        }
        assertThat(undocumented).as("served operations missing from messaging-api-v1.yaml").isEmpty();
    }

    @Test
    void committedSpecCoversTheWholeContractIncludingStageTwoEndpoints() throws Exception {
        assertThat(committedOperations()).containsAll(CONTRACT_ENDPOINTS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyOperationIsDescribedAndEveryReferenceResolves() throws Exception {
        Map<String, Object> spec = committedSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Set<String> operationIds = new LinkedHashSet<>();
        paths.forEach((p, item) -> ((Map<String, Object>) item).forEach((method, op) -> {
            if (!METHODS.contains(method)) {
                return;
            }
            Map<String, Object> operation = (Map<String, Object>) op;
            assertThat(operation).as(method + " " + p).containsKeys("operationId", "summary", "responses", "tags");
            assertThat(operationIds.add((String) operation.get("operationId"))).as("unique operationId " + operation.get("operationId")).isTrue();
            assertThat((Map<String, Object>) operation.get("responses")).as(method + " " + p + " responses").isNotEmpty();
        }));
        List<String> unresolved = new ArrayList<>();
        collectRefs(spec, spec, unresolved);
        assertThat(unresolved).as("unresolvable $ref values").isEmpty();
        assertThat(spec.get("openapi")).isEqualTo("3.0.3");
    }

    @SuppressWarnings("unchecked")
    private void collectRefs(Object node, Map<String, Object> root, List<String> unresolved) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if ("$ref".equals(k) && v instanceof String ref) {
                    Object cur = root;
                    for (String part : ref.substring(2).split("/")) {
                        cur = cur instanceof Map<?, ?> cm ? cm.get(part) : null;
                    }
                    if (cur == null) {
                        unresolved.add(ref);
                    }
                } else {
                    collectRefs(v, root, unresolved);
                }
            });
        } else if (node instanceof List<?> l) {
            l.forEach(i -> collectRefs(i, root, unresolved));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorCodeEnumInTheSpecMatchesTheJavaEnum() throws Exception {
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) committedSpec().get("components")).get("schemas");
        List<String> documented = (List<String>) ((Map<String, Object>) schemas.get("ErrorCode")).get("enum");
        List<String> actual = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
        assertThat(documented).containsExactlyInAnyOrderElementsOf(actual);
    }

    @Test
    @SuppressWarnings("unchecked")
    void enumsInTheSpecMatchTheDomainEnums() throws Exception {
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) committedSpec().get("components")).get("schemas");
        Map<String, Class<? extends Enum<?>>> enums = new LinkedHashMap<>();
        enums.put("Channel", com.example.messaging.domain.Channel.class);
        enums.put("Priority", com.example.messaging.domain.Priority.class);
        enums.put("Category", com.example.messaging.domain.Category.class);
        enums.put("MessageStatus", com.example.messaging.domain.MessageStatus.class);
        enums.put("TemplateStatus", com.example.messaging.domain.TemplateStatus.class);
        enums.put("DeliveryEventType", com.example.messaging.domain.DeliveryEventType.class);
        enums.put("ProviderOutcome", com.example.messaging.provider.ProviderOutcome.class);
        enums.put("DlqReason", com.example.messaging.domain.DlqReason.class);
        enums.forEach((name, type) -> {
            List<String> documented = (List<String>) ((Map<String, Object>) schemas.get(name)).get("enum");
            assertThat(documented).as(name).containsExactlyInAnyOrderElementsOf(
                    Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
        });
    }

    @Test
    void theExportedCopyUnderDocsApiIsIdenticalToTheCommittedSpec() throws Exception {
        Path exported = Path.of("..", "docs", "api", "openapi.yaml");
        assertThat(exported).as("docs/api/openapi.yaml must be a copy of messaging-api-v1.yaml").exists();
        String committed;
        try (InputStream in = new ClassPathResource("openapi/messaging-api-v1.yaml").getInputStream()) {
            committed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String copy = Files.readString(exported, StandardCharsets.UTF_8);
        assertThat(copy.replace("\r\n", "\n")).isEqualTo(committed.replace("\r\n", "\n"));
    }
}
