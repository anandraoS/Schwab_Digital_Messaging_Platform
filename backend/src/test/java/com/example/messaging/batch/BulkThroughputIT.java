package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Local throughput measurement of the bulk job (docs/operations/bulk-test-plan.md). Not part of the normal build: it
 * runs only with {@code -Dbulk.measure=true}. Options: {@code -Dbulk.rows=20000}, {@code -Dbulk.concurrency=4},
 * {@code -Dbulk.chunk=500}. The result line is printed and appended to {@code target/bulk-measurements.txt}.
 *
 * <p>What is measured: a CSV upload through the API, validation, duplicate detection, template check, submission of one
 * message per valid row (messages, outbox rows, audit rows, idempotency records) and the counters, on an in-memory
 * H2 database and one JVM. Provider dispatch is not part of it.
 */
@EnabledIfSystemProperty(named = "bulk.measure", matches = "true")
@TestPropertySource(properties = {
        "messaging.bulk.skip-limit=1000000",
        "messaging.bulk.max-rows=1000000",
        "messaging.bulk.max-upload-bytes=209715200",
        "messaging.bulk.upload-dir=target/bulk-measure",
        "messaging.bulk.sweeper-enabled=false",
        "spring.servlet.multipart.max-file-size=200MB",
        "spring.servlet.multipart.max-request-size=201MB"})
class BulkThroughputIT extends AbstractBulkIT {

    @DynamicPropertySource
    static void dynamic(DynamicPropertyRegistry r) {
        r.add("messaging.bulk.concurrency", () -> System.getProperty("bulk.concurrency", "4"));
        r.add("messaging.bulk.chunk-size", () -> System.getProperty("bulk.chunk", "500"));
    }

    @Test
    void measure() throws Exception {
        int rows = Integer.getInteger("bulk.rows", 20_000);
        int concurrency = Integer.getInteger("bulk.concurrency", 4);
        int chunk = Integer.getInteger("bulk.chunk", 500);

        long p0 = System.nanoTime();
        provisioner.ensure(RETAIL, rows);
        double provisionSeconds = (System.nanoTime() - p0) / 1e9;

        Path csv = Path.of("target/bulk-measure-input-" + rows + ".csv").toAbsolutePath();
        Files.createDirectories(csv.getParent());
        var gen = SyntheticDatasetGenerator.generate(csv, SyntheticDatasetGenerator.Options.of(rows, 0.02, 0.01).withSeed(5));
        byte[] content = Files.readAllBytes(csv);

        String auth = bulkSender(RETAIL);
        long t0 = System.nanoTime();
        MvcResult accepted = upload(auth, content);
        assertThat(accepted.getResponse().getStatus()).as(accepted.getResponse().getContentAsString()).isEqualTo(202);
        String id = json(accepted).get("id").asText();
        double acceptSeconds = (System.nanoTime() - t0) / 1e9;
        String status = awaitTerminal(id, Duration.ofMinutes(20));
        double seconds = (System.nanoTime() - t0) / 1e9;

        long[] c = counters(id);
        assertThat(status).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(c[2]).as("succeeded").isEqualTo(gen.valid());
        assertThat(c[4]).as("duplicates").isEqualTo(gen.duplicates());
        assertThat(c[1]).isEqualTo(rows);
        assertThat(distinctKeyCount(id)).isEqualTo(gen.valid());

        Runtime rt = Runtime.getRuntime();
        String line = String.format("%s rows=%d concurrency=%d chunk=%d status=%s seconds=%.2f rows_per_sec=%.0f "
                        + "succeeded=%d failed=%d duplicate=%d upload_accept_seconds=%.2f provision_seconds=%.2f "
                        + "csv_bytes=%d cpus=%d jvm=%s heap_max_mb=%d os=%s db=H2-in-memory",
                Instant.now(), rows, concurrency, chunk, status, seconds, rows / seconds, c[2], c[3], c[4], acceptSeconds,
                provisionSeconds, content.length, rt.availableProcessors(), System.getProperty("java.vm.name") + " "
                        + System.getProperty("java.version"), rt.maxMemory() >> 20, System.getProperty("os.name"));
        System.out.println("BULK-MEASUREMENT " + line);
        Files.writeString(Path.of("target/bulk-measurements.txt"), line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
