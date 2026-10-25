package com.example.messaging.batch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Streaming generator of synthetic bulk CSV files (constant memory: one buffered writer and a small ring of recently
 * written references, regardless of the row count). All values are fictitious.
 *
 * <p>Header: {@code externalRef,locale,var_accountLast4,var_firstName,var_side,var_quantity,var_symbol,var_price}
 * (the variables of the seeded {@code TRADE_CONFIRMATION} templates).
 *
 * <h2>Which recipients do the rows point at?</h2>
 * <ul>
 *   <li>{@code --refs=synthetic} (default): {@code SYN-0000001, SYN-0000002, ...}. These recipients do not exist in
 *       the seed. {@code POST /api/v1/bulk-jobs/generate-synthetic} creates them for the caller's tenant first
 *       ({@link SyntheticRecipientProvisioner}, switch off with {@code ?createRecipients=false}); a job run on a CLI
 *       generated file without provisioning reports every such row as {@code RECIPIENT_NOT_FOUND} in
 *       {@code bulk_job_failure} - which is itself a useful failure-path load test.</li>
 *   <li>{@code --refs=seed}: the seeded recipients {@code CUST-001..CUST-040} (tenant-retail). Limited to
 *       {@code --seed-recipients} rows because every reference must be unique.</li>
 * </ul>
 *
 * <h2>Defects</h2>
 * Each row is, in this order of precedence, <i>invalid</i> with probability {@code invalidRate} (missing reference,
 * unknown recipient, wrong column count, formula injection, over-long field, bad locale), a <i>duplicate</i> of one of
 * the last 1000 valid references with probability {@code duplicateRate}, otherwise valid.
 *
 * <p>Usage: {@code SyntheticDatasetGenerator <rows> <outFile> [dupRate] [invalidRate] [--seed=N] [--refs=synthetic|seed]
 * [--seed-recipients=40] [--start=1]}
 */
public final class SyntheticDatasetGenerator {

    public static final List<String> DEFAULT_VARIABLES = List.of("accountLast4", "firstName", "side", "quantity",
            "symbol", "price");
    /** Header of the default (TRADE_CONFIRMATION) layout. */
    public static final String HEADER = header(DEFAULT_VARIABLES);
    private static final int RING = 1000;
    private static final String[] NAMES = {"Alex", "Blake", "Casey", "Dana", "Eli", "Fran", "Gale", "Harper", "Indy",
            "Jules", "Kai", "Lane", "Morgan", "Nico", "Oakley", "Parker"};
    private static final String[] SYMBOLS = {"ACME", "GLOBX", "INITECH", "UMBRA", "HOOLI", "STARK", "WAYNE", "TYREL"};

    public enum RefMode { SYNTHETIC, SEED }

    public record Options(long rows, double duplicateRate, double invalidRate, Long seed, RefMode refs,
            int seedRecipients, long startIndex, List<String> variables) {

        public Options {
            if (rows < 1) {
                throw new IllegalArgumentException("rows must be >= 1");
            }
            if (duplicateRate < 0 || duplicateRate > 1 || invalidRate < 0 || invalidRate > 1
                    || duplicateRate + invalidRate > 1) {
                throw new IllegalArgumentException("rates must be within [0,1] and sum to at most 1");
            }
            variables = variables == null ? DEFAULT_VARIABLES : List.copyOf(variables);
            if (refs == RefMode.SEED && rows > seedRecipients) {
                throw new IllegalArgumentException("--refs=seed supports at most " + seedRecipients
                        + " rows (unique seeded references); use --refs=synthetic");
            }
        }

        public static Options of(long rows, double duplicateRate, double invalidRate) {
            return new Options(rows, duplicateRate, invalidRate, null, RefMode.SYNTHETIC, 40, 1, DEFAULT_VARIABLES);
        }

        public Options withSeed(long s) {
            return new Options(rows, duplicateRate, invalidRate, s, refs, seedRecipients, startIndex, variables);
        }

        /** Columns {@code var_<name>} to generate (use the variables a template declares). */
        public Options withVariables(List<String> names) {
            return new Options(rows, duplicateRate, invalidRate, seed, refs, seedRecipients, startIndex, names);
        }
    }

    /** What was written. {@code valid + duplicates + invalid == rows}. */
    public record Result(long rows, long valid, long duplicates, long invalid) {}

    private SyntheticDatasetGenerator() {}

    public static Result generate(Path out, Options o) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            return generate(w, o);
        }
    }

    /** Streams to any writer (the caller closes it). */
    public static Result generate(Writer w, Options o) throws IOException {
        Random rnd = o.seed() == null ? new Random() : new Random(o.seed());
        String[] ring = new String[RING];
        int ringSize = 0;
        int ringPos = 0;
        long valid = 0;
        long dup = 0;
        long invalid = 0;
        StringBuilder sb = new StringBuilder(160);
        w.write(header(o.variables()));
        w.write('\n');
        for (long i = 0; i < o.rows(); i++) {
            double roll = rnd.nextDouble();
            sb.setLength(0);
            if (roll < o.invalidRate()) {
                invalid++;
                appendInvalid(sb, rnd, i, o.variables());
            } else if (roll < o.invalidRate() + o.duplicateRate() && ringSize > 0) {
                dup++;
                appendRow(sb, ring[rnd.nextInt(ringSize)], o.variables(), rnd, i);
            } else {
                String ref = ref(o, i);
                appendRow(sb, ref, o.variables(), rnd, i);
                ring[ringPos] = ref;
                ringPos = (ringPos + 1) % RING;
                ringSize = Math.min(RING, ringSize + 1);
                valid++;
            }
            sb.append('\n');
            w.write(sb.toString());
        }
        w.flush();
        return new Result(o.rows(), valid, dup, invalid);
    }

    private static String ref(Options o, long i) {
        return o.refs() == RefMode.SEED
                ? String.format("CUST-%03d", i + 1)
                : String.format("SYN-%07d", o.startIndex() + i);
    }

    public static String header(List<String> variables) {
        StringBuilder sb = new StringBuilder("externalRef,locale");
        variables.forEach(v -> sb.append(",var_").append(v));
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, String ref, List<String> vars, Random rnd, long i) {
        sb.append(ref).append(',').append(i % 5 == 4 ? "es" : "en");
        for (String v : vars) {
            sb.append(',').append(value(v, rnd));
        }
    }

    /** Plausible fictitious value for the well-known variable names, a short token for any other. */
    private static String value(String name, Random rnd) {
        return switch (name) {
            case "accountLast4" -> String.format("%04d", rnd.nextInt(10000));
            case "firstName" -> NAMES[rnd.nextInt(NAMES.length)];
            case "side" -> rnd.nextBoolean() ? "buy" : "sell";
            case "quantity" -> Integer.toString(1 + rnd.nextInt(500));
            case "symbol" -> SYMBOLS[rnd.nextInt(SYMBOLS.length)];
            case "price" -> String.format("%d.%02d", 1 + rnd.nextInt(900), rnd.nextInt(100));
            case "time" -> String.format("%02d:%02d", rnd.nextInt(24), rnd.nextInt(60));
            case "offerTitle" -> "Spring offer " + (1 + rnd.nextInt(50));
            default -> "val" + rnd.nextInt(1000);
        };
    }

    private static void appendInvalid(StringBuilder sb, Random rnd, long i, List<String> vars) {
        int kind = rnd.nextInt(6);
        if (vars.isEmpty() && (kind == 3 || kind == 4)) {
            kind = 1; // nothing to corrupt: use an unknown recipient instead
        }
        switch (kind) {
            case 0 -> appendRow(sb, "", vars, rnd, i); // missing reference
            case 1 -> appendRow(sb, String.format("NOPE-%07d", i), vars, rnd, i); // recipient that does not exist
            case 2 -> sb.append(String.format("BAD-%07d,en,1234,extra,columns,that,do,not,fit,at,all,here", i)); // wrong column count
            case 3 -> { // formula injection in the first variable
                appendRow(sb, String.format("SYN-X%07d", i), vars, rnd, i);
                replaceCell(sb, 2, "=1+1");
            }
            case 4 -> { // over-long field
                appendRow(sb, String.format("SYN-L%07d", i), vars, rnd, i);
                replaceCell(sb, 2, "A".repeat(2500));
            }
            default -> { // bad locale
                appendRow(sb, String.format("SYN-Z%07d", i), vars, rnd, i);
                replaceCell(sb, 1, "x");
            }
        }
    }

    /** Replaces the 0-based CSV cell {@code col} of a row built without quoting. */
    private static void replaceCell(StringBuilder sb, int col, String text) {
        int start = 0;
        for (int c = 0; c < col; c++) {
            start = sb.indexOf(",", start) + 1;
        }
        int end = sb.indexOf(",", start);
        sb.replace(start, end < 0 ? sb.length() : end, text);
    }

    // ------------------------------------------------------------------ CLI

    public static void main(String[] args) throws IOException {
        long rows = -1;
        Path out = null;
        double dup = 0.02;
        double inv = 0.02;
        Long seed = null;
        RefMode refs = RefMode.SYNTHETIC;
        int seedRecipients = 40;
        long start = 1;
        List<String> vars = null;
        int positional = 0;
        for (String a : args) {
            if (a.equals("--help") || a.equals("-h")) {
                usage();
                return;
            } else if (a.startsWith("--seed=")) {
                seed = Long.parseLong(a.substring("--seed=".length()));
            } else if (a.startsWith("--refs=")) {
                refs = RefMode.valueOf(a.substring("--refs=".length()).toUpperCase(java.util.Locale.ROOT));
            } else if (a.startsWith("--seed-recipients=")) {
                seedRecipients = Integer.parseInt(a.substring("--seed-recipients=".length()));
            } else if (a.startsWith("--vars=")) {
                vars = List.of(a.substring("--vars=".length()).split(","));
            } else if (a.startsWith("--start=")) {
                start = Long.parseLong(a.substring("--start=".length()));
            } else if (a.startsWith("--")) {
                System.err.println("unknown option " + a);
                usage();
                System.exit(2);
            } else {
                switch (positional++) {
                    case 0 -> rows = Long.parseLong(a);
                    case 1 -> out = Path.of(a);
                    case 2 -> dup = Double.parseDouble(a);
                    case 3 -> inv = Double.parseDouble(a);
                    default -> {
                        usage();
                        System.exit(2);
                    }
                }
            }
        }
        if (rows < 1 || out == null) {
            usage();
            System.exit(2);
        }
        Result r = generate(out, new Options(rows, dup, inv, seed, refs, seedRecipients, start, vars));
        System.out.println("rows=" + r.rows() + " valid=" + r.valid() + " duplicates=" + r.duplicates()
                + " invalid=" + r.invalid() + " file=" + out);
    }

    private static void usage() {
        System.err.println("usage: SyntheticDatasetGenerator <rows> <outFile> [dupRate=0.02] [invalidRate=0.02] "
                + "[--seed=N] [--refs=synthetic|seed] [--seed-recipients=40] [--start=1] [--vars=a,b,c]");
    }
}
