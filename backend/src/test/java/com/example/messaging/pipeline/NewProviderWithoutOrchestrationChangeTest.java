package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.callback.ProviderPayloadMapper;
import com.example.messaging.callback.SignatureVerifier;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.provider.ChannelProvider;
import com.example.messaging.provider.ProviderRequest;
import com.example.messaging.provider.ProviderResult;
import com.example.messaging.provider.ProviderStatus;
import com.example.messaging.provider.SmsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-0002 / spec 0002: adding a provider is a bean, a {@code provider_config} row and a payload mapper, nothing in
 * the orchestration. Proven three ways: (1) the seeded Acme SMS simulator is switched on purely through the config
 * API, (2) a brand new vendor, defined in this test only, is routed and its callbacks normalised without touching any
 * main class, (3) a source-level rule: orchestration packages never name a concrete provider.
 */
@Import(NewProviderWithoutOrchestrationChangeTest.VendorXConfig.class)
@TestPropertySource(properties = "messaging.callback.secrets.vendorx-sms-key=local-dev-secret-not-for-production")
class NewProviderWithoutOrchestrationChangeTest extends AbstractPipelineIT {

    // ------------------------------------------------------------------ a brand new vendor, test-only code

    static final class VendorXSmsProvider implements SmsProvider {
        final AtomicInteger calls = new AtomicInteger();
        final Map<String, String> byRef = new ConcurrentHashMap<>();

        @Override
        public String providerId() {
            return "vendorx-sms";
        }

        @Override
        public ProviderResult send(ProviderRequest request) {
            calls.incrementAndGet();
            String id = byRef.computeIfAbsent(request.idempotencyRef(), r -> "vx-" + (byRef.size() + 1));
            return ProviderResult.accepted(id, 1);
        }

        @Override
        public Optional<ProviderStatus> lookup(String idempotencyRef) {
            String id = byRef.get(idempotencyRef);
            return Optional.of(id == null ? new ProviderStatus(ProviderStatus.State.NOT_RECEIVED, null, null)
                    : new ProviderStatus(ProviderStatus.State.ACCEPTED, id, Instant.now()));
        }
    }

    /** VendorX webhooks look like {@code {"vx":"vx-1","outcome":"DONE","eid":"e1"}}. */
    static final class VendorXMapper implements ProviderPayloadMapper {
        @Override
        public String providerId() {
            return "vendorx-sms";
        }

        @Override
        public NormalizedEvent map(JsonNode root, String headerEventId, Instant receivedAt) {
            String outcome = root.path("outcome").asText();
            DeliveryEventType type = switch (outcome) {
                case "DONE" -> DeliveryEventType.DELIVERED;
                case "NOPE" -> DeliveryEventType.FAILED;
                default -> throw new com.example.messaging.callback.MalformedCallbackException("UNKNOWN_STATUS");
            };
            return new NormalizedEvent("vendorx-sms", root.path("eid").asText(headerEventId), type,
                    root.path("vx").asText(null), null, receivedAt, null);
        }

        @Override
        public String render(CallbackSpec spec) {
            return "{\"vx\":\"" + spec.providerMessageId() + "\",\"outcome\":\""
                    + (spec.type() == DeliveryEventType.DELIVERED ? "DONE" : "NOPE") + "\",\"eid\":\"" + spec.eventId()
                    + "\"}";
        }
    }

    @TestConfiguration
    static class VendorXConfig {
        @Bean
        VendorXSmsProvider vendorXSmsProvider() {
            return new VendorXSmsProvider();
        }

        @Bean
        VendorXMapper vendorXMapper() {
            return new VendorXMapper();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    VendorXSmsProvider vendorX;

    // ------------------------------------------------------------------ (1) Acme, by configuration only

    @Test
    void acmeSmsIsDisabledByDefaultAndSwitchedOnPurelyThroughConfiguration() throws Exception {
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("simulated-sms");
        String before = submit(newRecipient(RETAIL), "SMS");
        awaitStatus(before, "SENT_TO_PROVIDER");
        assertThat(attemptList(before).get(0)).containsEntry("provider_id", "simulated-sms");
        assertThat(acme.callCount()).isZero();
        long smsCallsBefore = sms.callCount();

        // operator switches the vendor over: acme on, simulated-sms off. No restart, no code.
        String admin = platformAdmin();
        mvc.perform(put("/api/v1/providers/acme-sms/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/providers/simulated-sms/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());

        String id = submit(newRecipient(RETAIL), "SMS");
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(attemptList(id).get(0)).containsEntry("provider_id", "acme-sms").containsEntry("outcome", "ACCEPTED");
        assertThat((String) attemptList(id).get(0).get("provider_message_id")).startsWith("acme-");
        assertThat(acme.callCount()).isEqualTo(1);
        assertThat(sms.callCount()).isEqualTo(smsCallsBefore); // the old vendor saw nothing of the second message

        // Acme's own callback format ({"ref","state","ts","id"}) is normalised by its mapper bean
        String body = payload(id, DeliveryEventType.DELIVERED, uniqueEventId(), Instant.now());
        assertThat(body).contains("\"state\":\"ok\"").contains("\"ref\":\"acme-");
        String sig = SignatureVerifier.header(SECRET, Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8));
        assertThat(postCallback("acme-sms", keyIdFor("acme-sms"), sig, uniqueEventId(), body).status()).isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        // other vendors' secrets do not work for Acme
        assertThat(postCallback("acme-sms", keyIdFor("simulated-sms"), sig, uniqueEventId(), body).status()).isEqualTo(401);
    }

    @Test
    void priorityOrderAndCircuitStateDriveFailoverBetweenTheTwoSmsVendors() throws Exception {
        jdbc.update("update provider_config set enabled = true where provider_id = 'acme-sms'"); // order 2
        registry.invalidate();
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("simulated-sms");
        invoker.open("simulated-sms"); // circuit breaker of the preferred vendor opens
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("acme-sms");
        invoker.reset("simulated-sms");
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("simulated-sms");
        jdbc.update("update provider_config set enabled = false where provider_id in ('simulated-sms','acme-sms')");
        registry.invalidate();
        assertThat(registry.select(Channel.SMS)).isEmpty();
        // all open: the first configured provider is still returned (the consumer then parks the message)
        jdbc.update("update provider_config set enabled = true where provider_id in ('simulated-sms','acme-sms')");
        registry.invalidate();
        invoker.open("simulated-sms");
        invoker.open("acme-sms");
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("simulated-sms");
    }

    // ------------------------------------------------------------------ (2) a vendor the main code has never heard of

    @Test
    void aBrandNewVendorIsRoutedCalledAndNormalisedWithoutChangingAnyMainClass() throws Exception {
        long now = System.currentTimeMillis();
        jdbc.update("insert into provider_config (id, provider_id, channel, enabled, priority_order, rate_limit_per_sec, "
                + "timeout_ms, hmac_key_ref, created_at, updated_at, version) values (?,?,?,?,?,?,?,?,?,?,0)",
                "prv-vx-" + now, "vendorx-sms", "SMS", true, 0, 100, 2000, "vendorx-sms-key",
                utc(Instant.now()), utc(Instant.now()));
        registry.invalidate();
        try {
            assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("vendorx-sms");
            String id = submit(newRecipient(RETAIL), "SMS");
            awaitStatus(id, "SENT_TO_PROVIDER");
            assertThat(attemptList(id).get(0)).containsEntry("provider_id", "vendorx-sms");
            assertThat(vendorX.calls.get()).isEqualTo(1);
            assertThat(sms.callCount()).isZero();

            String body = "{\"vx\":\"vx-1\",\"outcome\":\"DONE\",\"eid\":\"vx-evt-1\"}";
            String sig = SignatureVerifier.header(SECRET, Instant.now().getEpochSecond(),
                    body.getBytes(StandardCharsets.UTF_8));
            assertThat(postCallback("vendorx-sms", "vendorx-sms-key", sig, "vx-hdr-1", body).status()).isEqualTo(202);
            assertThat(statusOf(id)).isEqualTo("DELIVERED");
            // unknown vendor status is rejected as unintelligible by its own mapper
            String bad = "{\"vx\":\"vx-1\",\"outcome\":\"???\",\"eid\":\"vx-evt-2\"}";
            assertThat(postCallback("vendorx-sms", "vendorx-sms-key", SignatureVerifier.header(SECRET,
                    Instant.now().getEpochSecond(), bad.getBytes(StandardCharsets.UTF_8)), "vx-hdr-2", bad).status())
                    .isEqualTo(400);
        } finally {
            jdbc.update("update message_request set status = 'CANCELLED' where status not in ('DELIVERED','REJECTED',"
                    + "'CANCELLED','FAILED','DEAD_LETTERED')");
            jdbc.update("delete from provider_config where provider_id = 'vendorx-sms'");
            registry.invalidate();
        }
    }

    // ------------------------------------------------------------------ (3) the architectural rule

    @Test
    void orchestrationPackagesNeverNameAConcreteProvider() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "example", "messaging");
        List<String> forbidden = List.of("SimulatedEmailProvider", "SimulatedSmsProvider", "SimulatedPushProvider",
                "AcmeSmsProvider", "FaultInjector", "SimulatedProviderBase", "acme-sms", "simulated-");
        for (String pkg : List.of("messaging", "service", "scheduler", "callback", "domain")) {
            try (Stream<Path> files = Files.walk(root.resolve(pkg))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(f);
                    for (String word : forbidden) {
                        // the callback package may name vendor ids in the mappers that serve them
                        if (pkg.equals("callback") && f.getFileName().toString().matches(
                                "(SimulatedEmail|SimulatedSms|SimulatedPush|AcmeSms)PayloadMapper.java")) {
                            continue;
                        }
                        assertThat(text).as("%s must not mention %s", root.relativize(f), word).doesNotContain(word);
                    }
                }
            }
        }
        // sanity: the adapters are only reachable through the registry
        ChannelProvider anyProvider = registry.adapter("acme-sms").orElseThrow();
        assertThat(anyProvider.channel()).isEqualTo(Channel.SMS);
    }
}
