package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.messaging.TestProps;
import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.config.MessagingProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BulkUploadStorageTest {

    static final String JOB = "11111111-2222-3333-4444-555555555555";
    static final String HEADER = "externalRef,locale,var_a";

    @TempDir
    Path dir;

    BulkUploadStorage storage(int maxRows, long maxBytes, int maxLine) {
        MessagingProperties base = TestProps.defaults();
        MessagingProperties props = new MessagingProperties(base.dispatch(), base.backpressure(), base.bus(),
                base.coordination(), base.providers(), base.callback(), base.api(),
                new MessagingProperties.Bulk(4, 500, 100, maxRows, dir.toString()), base.retention(), base.security(),
                base.templates(), base.idempotency());
        BulkProperties bulk = new BulkProperties(maxBytes, maxLine, 51, 0, 1000, 3, 1000, 2, 20, Duration.ofMinutes(30),
                Duration.ofSeconds(60), Duration.ofSeconds(10), false, true);
        return new BulkUploadStorage(props, bulk);
    }

    BulkUploadStorage storage() {
        return storage(100, 1 << 20, 200);
    }

    static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    static void assertApi(Runnable r, ErrorCode code, String fragment) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.getMessage() + e.getDetails()).contains(fragment);
        });
    }

    long filesUnder(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    // ------------------------------------------------------------------ names

    @Test
    void sanitizeFileName_keepsOnlyASafeBaseName() {
        assertThat(BulkUploadStorage.sanitizeFileName("../../etc/passwd")).isEqualTo("passwd.csv");
        assertThat(BulkUploadStorage.sanitizeFileName("..\\..\\windows\\evil.csv")).isEqualTo("evil.csv");
        assertThat(BulkUploadStorage.sanitizeFileName("my file (1).CSV")).isEqualTo("my file _1_.CSV");
        assertThat(BulkUploadStorage.sanitizeFileName("a\r\nb.csv")).isEqualTo("a__b.csv");
        assertThat(BulkUploadStorage.sanitizeFileName(".hidden")).isEqualTo("upload.hidden.csv");
        assertThat(BulkUploadStorage.sanitizeFileName("")).isEqualTo("upload.csv");
        assertThat(BulkUploadStorage.sanitizeFileName(null)).isEqualTo("upload.csv");
        assertThat(BulkUploadStorage.sanitizeFileName("<script>.csv")).isEqualTo("_script_.csv");
        assertThat(BulkUploadStorage.sanitizeFileName("x".repeat(300) + ".csv").length()).isLessThanOrEqualTo(104);
        assertThat(BulkUploadStorage.sanitizeFileName("a....b.csv")).isEqualTo("a.b.csv");
    }

    @Test
    void contentTypeAndExtension_areChecked() {
        BulkUploadStorage.checkContentType("text/csv; charset=utf-8", "a.csv");
        BulkUploadStorage.checkContentType("application/vnd.ms-excel", "a.CSV");
        BulkUploadStorage.checkContentType(null, "a.csv");
        BulkUploadStorage.checkContentType("application/octet-stream", "a.csv");
        assertApi(() -> BulkUploadStorage.checkContentType("text/csv", "a.xlsx"), ErrorCode.VALIDATION_FAILED, ".csv");
        assertApi(() -> BulkUploadStorage.checkContentType("text/html", "a.csv"), ErrorCode.VALIDATION_FAILED, "content type");
        assertApi(() -> BulkUploadStorage.checkContentType("application/x-sh", "a.csv"), ErrorCode.VALIDATION_FAILED,
                "content type");
    }

    // ------------------------------------------------------------------ paths

    @Test
    void paths_cannotEscapeTheUploadDirectory() {
        BulkUploadStorage s = storage();
        assertThatThrownBy(() -> s.requireInsideUploadDir(dir.resolve("..").resolve("outside.csv")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.requireInsideUploadDir(Path.of("/etc/passwd"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.requireInsideUploadDir(dir.resolve("t").resolve("x.csv")).startsWith(dir.toAbsolutePath().normalize())).isTrue();
        // hostile tenant ids never become path elements
        for (String tenant : List.of("../x", "a/b", "a\\b", "", "t\u0000", "..")) {
            assertApi(() -> s.store(tenant, JOB, in(HEADER + "\nA,en,x\n"), "a.csv", "text/csv"),
                    ErrorCode.VALIDATION_FAILED, "tenant");
        }
        // hostile job ids are refused as well
        assertThatThrownBy(() -> s.newJobFile("tenant-a", "../../evil")).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ storing

    @Test
    void store_normalisesTheFile_andCountsRows() throws IOException {
        String raw = (char) 0xFEFF + HEADER + "\r\nA,en,x\r\n\r\nB,en,y\r\nC,,z\r\n\r\n\r\n"; // BOM, CRLF, blank lines
        var stored = storage().store("tenant-a", JOB, in(raw), "../../Q1 list.csv", "text/csv");

        assertThat(stored.rows()).isEqualTo(3);
        assertThat(stored.displayName()).isEqualTo("Q1 list.csv");
        assertThat(stored.path()).isEqualTo(dir.toAbsolutePath().normalize().resolve("tenant-a").resolve(JOB + ".csv"));
        // BOM gone, LF endings, the interior blank line kept (line numbers stay true), trailing blanks dropped
        assertThat(Files.readString(stored.path())).isEqualTo(HEADER + "\nA,en,x\n\nB,en,y\nC,,z\n");
        assertThat(stored.bytes()).isEqualTo(raw.getBytes(StandardCharsets.UTF_8).length);
        assertThat(filesUnder(dir)).isEqualTo(1);
    }

    @Test
    void store_enforcesRowLimit_andLeavesNothingBehind() throws IOException {
        BulkUploadStorage s = storage(3, 1 << 20, 200);
        s.store("t", JOB, in(HEADER + "\nA,en,x\nB,en,x\nC,en,x\n"), "a.csv", "text/csv"); // exactly at the limit
        Files.delete(dir.resolve("t").resolve(JOB + ".csv"));

        assertApi(() -> s.store("t", JOB, in(HEADER + "\nA,en,x\nB,en,x\nC,en,x\nD,en,x\n"), "a.csv", "text/csv"),
                ErrorCode.PAYLOAD_TOO_LARGE, "3 data rows");
        assertThat(filesUnder(dir)).isZero();
    }

    @Test
    void store_enforcesByteLimitWhileStreaming() throws IOException {
        BulkUploadStorage s = storage(1000, 200, 200);
        String big = HEADER + "\n" + "A,en,xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n".repeat(20);
        assertApi(() -> s.store("t", JOB, in(big), "a.csv", "text/csv"), ErrorCode.PAYLOAD_TOO_LARGE, "200 bytes");
        assertThat(filesUnder(dir)).isZero();
    }

    @Test
    void store_rejectsOverlongLines_invalidUtf8_badHeadersAndEmptyContent() throws IOException {
        BulkUploadStorage s = storage(100, 1 << 20, 50);
        assertApi(() -> s.store("t", JOB, in(HEADER + "\nA,en," + "x".repeat(60) + "\n"), "a.csv", "text/csv"),
                ErrorCode.VALIDATION_FAILED, "line 2");
        assertApi(() -> s.store("t", JOB, new ByteArrayInputStream(new byte[] {'e', 'x', 'i', (byte) 0xC3, 0x28, '\n', 'A'}),
                "a.csv", "text/csv"), ErrorCode.VALIDATION_FAILED, "UTF-8");
        assertApi(() -> s.store("t", JOB, in("nope,columns\nA,B\n"), "a.csv", "text/csv"), ErrorCode.VALIDATION_FAILED,
                "INVALID_HEADER");
        assertApi(() -> s.store("t", JOB, in(HEADER + "\n"), "a.csv", "text/csv"), ErrorCode.VALIDATION_FAILED, "no data rows");
        assertApi(() -> s.store("t", JOB, in(HEADER + "\n\n\n"), "a.csv", "text/csv"), ErrorCode.VALIDATION_FAILED, "no data rows");
        assertApi(() -> s.store("t", JOB, in(""), "a.csv", "text/csv"), ErrorCode.VALIDATION_FAILED, "empty");
        assertApi(() -> s.store("t", JOB, in("x".repeat(80)), "a.csv", "text/csv"), ErrorCode.VALIDATION_FAILED, "too long");
        assertThat(filesUnder(dir)).isZero();
    }

    @Test
    void store_refusesToOverwriteAnExistingJobFile() throws IOException {
        BulkUploadStorage s = storage();
        s.store("t", JOB, in(HEADER + "\nA,en,x\n"), "a.csv", "text/csv");
        assertThatThrownBy(() -> s.store("t", JOB, in(HEADER + "\nB,en,x\n"), "b.csv", "text/csv"))
                .isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(Files.readString(dir.resolve("t").resolve(JOB + ".csv"))).contains("A,en,x"); // original intact
    }
}
