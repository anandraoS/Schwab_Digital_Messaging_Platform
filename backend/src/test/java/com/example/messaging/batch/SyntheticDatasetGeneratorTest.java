package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntheticDatasetGeneratorTest {

    @TempDir
    Path dir;

    private static String generate(SyntheticDatasetGenerator.Options o) throws IOException {
        StringWriter w = new StringWriter();
        SyntheticDatasetGenerator.generate(w, o);
        return w.toString();
    }

    @Test
    void sameSeed_givesByteIdenticalOutput_differentSeedDoesNot() throws IOException {
        var o = SyntheticDatasetGenerator.Options.of(5000, 0.05, 0.05).withSeed(42);
        String a = generate(o);
        String b = generate(o);
        String c = generate(SyntheticDatasetGenerator.Options.of(5000, 0.05, 0.05).withSeed(43));
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.lines().count()).isEqualTo(5001); // header + rows
    }

    @Test
    void headerAndLayout_matchTheTradeConfirmationTemplate() throws IOException {
        String csv = generate(SyntheticDatasetGenerator.Options.of(10, 0, 0).withSeed(1));
        List<String> lines = csv.lines().toList();
        assertThat(lines.get(0)).isEqualTo(
                "externalRef,locale,var_accountLast4,var_firstName,var_side,var_quantity,var_symbol,var_price");
        assertThat(lines.get(0)).isEqualTo(SyntheticDatasetGenerator.HEADER);
        for (int i = 1; i <= 10; i++) {
            String[] cells = lines.get(i).split(",", -1);
            assertThat(cells).hasSize(8);
            assertThat(cells[0]).isEqualTo(String.format("SYN-%07d", i));
            assertThat(cells[1]).isIn("en", "es");
            assertThat(cells[2]).matches("\\d{4}");
        }
    }

    @Test
    void duplicateAndInvalidRates_areHonoured_andCountsAddUp() throws IOException {
        long rows = 200_000;
        StringWriter w = new StringWriter();
        var r = SyntheticDatasetGenerator.generate(w, SyntheticDatasetGenerator.Options.of(rows, 0.05, 0.10).withSeed(7));
        assertThat(r.rows()).isEqualTo(rows);
        assertThat(r.valid() + r.duplicates() + r.invalid()).isEqualTo(rows);
        assertThat(r.invalid() / (double) rows).isBetween(0.095, 0.105);
        assertThat(r.duplicates() / (double) rows).isBetween(0.045, 0.055);

        // what is in the file agrees with the counters: every valid row has a distinct reference ...
        Set<String> refs = new HashSet<>();
        long dup = 0;
        long bad = 0;
        for (String line : w.toString().lines().skip(1).toList()) {
            if (isDefective(line)) {
                bad++;
                continue;
            }
            if (!refs.add(line.substring(0, line.indexOf(',')))) {
                dup++;
            }
        }
        assertThat(dup).isEqualTo(r.duplicates());
        // ... and defective rows are those the validator rejects, or unknown NOPE- recipients (valid CSV, no recipient)
        assertThat(bad).isEqualTo(r.invalid());
    }

    /** A row is defective when the CSV rules reject it or it points at a recipient that cannot exist. */
    private static boolean isDefective(String line) {
        String[] c = line.split(",", -1);
        if (c.length != 8 || c[0].startsWith("NOPE-")) {
            return true;
        }
        try {
            CsvRowValidator.validateExternalRef(c[0]);
            CsvRowValidator.validateLocale(c[1]);
            for (int i = 2; i < c.length; i++) {
                CsvRowValidator.validateValue("v", c[i], 2000);
            }
            return false;
        } catch (BulkRowException e) {
            return true;
        }
    }

    @Test
    void zeroRates_produceOnlyDistinctValidRows() throws IOException {
        var r = SyntheticDatasetGenerator.generate(new StringWriter(), SyntheticDatasetGenerator.Options.of(1000, 0, 0));
        assertThat(r.valid()).isEqualTo(1000);
        assertThat(r.duplicates()).isZero();
        assertThat(r.invalid()).isZero();
    }

    @Test
    void invalidRows_coverEveryDefectKind() throws IOException {
        String csv = generate(SyntheticDatasetGenerator.Options.of(3000, 0, 1.0).withSeed(3));
        Set<String> kinds = new HashSet<>();
        for (String line : csv.lines().skip(1).toList()) {
            String[] c = line.split(",", -1);
            if (c.length != 8) {
                kinds.add("columns");
            } else if (c[0].isEmpty()) {
                kinds.add("missingRef");
            } else if (c[0].startsWith("NOPE-")) {
                kinds.add("unknownRecipient");
            } else if (c[2].startsWith("=")) {
                kinds.add("formula");
            } else if (c[2].length() > 2000) {
                kinds.add("longField");
            } else if (c[1].equals("x")) {
                kinds.add("badLocale");
            }
        }
        assertThat(kinds).containsExactlyInAnyOrder("columns", "missingRef", "unknownRecipient", "formula",
                "longField", "badLocale");
    }

    @Test
    void customVariableColumns_followTheTemplate() throws IOException {
        String csv = generate(SyntheticDatasetGenerator.Options.of(5, 0, 0).withSeed(1).withVariables(List.of("time")));
        List<String> lines = csv.lines().toList();
        assertThat(lines.get(0)).isEqualTo("externalRef,locale,var_time");
        assertThat(lines.get(1).split(",")).hasSize(3);
        // a template without variables still works, including defective rows
        String none = generate(SyntheticDatasetGenerator.Options.of(50, 0, 0.5).withSeed(1).withVariables(List.of()));
        assertThat(none.lines().findFirst().orElseThrow()).isEqualTo("externalRef,locale");
    }

    @Test
    void seedRecipients_modeUsesTheSeededReferences_andRefusesMoreRowsThanRecipients() throws IOException {
        var o = new SyntheticDatasetGenerator.Options(40, 0, 0, 1L, SyntheticDatasetGenerator.RefMode.SEED, 40, 1, null);
        List<String> lines = generate(o).lines().toList();
        assertThat(lines.get(1)).startsWith("CUST-001,");
        assertThat(lines.get(40)).startsWith("CUST-040,");
        assertThatThrownBy(() -> new SyntheticDatasetGenerator.Options(41, 0, 0, 1L, SyntheticDatasetGenerator.RefMode.SEED,
                40, 1, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--refs=synthetic");
    }

    @Test
    void optionsAreValidated() {
        assertThatThrownBy(() -> SyntheticDatasetGenerator.Options.of(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyntheticDatasetGenerator.Options.of(10, -0.1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyntheticDatasetGenerator.Options.of(10, 0.6, 0.6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mainWritesTheFile_withDefaultsAndExplicitOptions() throws IOException {
        Path out = dir.resolve("out").resolve("data.csv"); // parent directory is created
        SyntheticDatasetGenerator.main(new String[] {"100", out.toString(), "0", "0", "--seed=9"});
        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(101);
        assertThat(lines.get(0)).isEqualTo(SyntheticDatasetGenerator.HEADER);
        Path again = dir.resolve("again.csv");
        SyntheticDatasetGenerator.main(new String[] {"100", again.toString(), "0", "0", "--seed=9"});
        assertThat(Files.readString(again)).isEqualTo(Files.readString(out));
    }

    @Test
    void oneMillionRows_streamToDisk_inAnEmptyHeap_withinTheTimeBox() throws Exception {
        // Run the real main() in a child JVM whose heap (24 MB) is far smaller than the file (60+ MB): if the generator
        // kept rows in memory the child would die with OutOfMemoryError. Measuring heap in-process is unreliable
        // (System.gc() is only a hint), a hard -Xmx limit is not.
        Path out = dir.resolve("million.csv");
        Path classes = Path.of(SyntheticDatasetGenerator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-Xmx24m", "-XX:+UseSerialGC", "-cp", classes.toString(),
                SyntheticDatasetGenerator.class.getName(), "1000000", out.toString(), "0.02", "0.02", "--seed=11");
        pb.redirectErrorStream(true);
        long t0 = System.nanoTime();
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean done = p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;

        assertThat(done).as("finished within 90 s; output: " + output).isTrue();
        assertThat(p.exitValue()).as(output).isZero();
        assertThat(output).contains("rows=1000000");
        assertThat(Files.size(out)).as("file size").isGreaterThan(48L << 20); // larger than twice the child's heap
        try (var lines = Files.lines(out)) {
            assertThat(lines.count()).isEqualTo(1_000_001);
        }
        assertThat(seconds).isLessThan(90);
        System.out.println("generator 1,000,000 rows in a 24 MB heap: " + String.format("%.1f", seconds) + " s");
    }
}
