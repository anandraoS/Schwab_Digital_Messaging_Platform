package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemReader;

/** Header rules, the line reader and the streaming CSV reader. */
class CsvRulesTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ header

    @Test
    void validHeader_isParsedInFileOrder() {
        var h = CsvRowValidator.validateHeader(List.of("var_a", "externalRef", "locale", "var_b2"), 51);
        assertThat(h.refIndex()).isEqualTo(1);
        assertThat(h.localeIndex()).isEqualTo(2);
        assertThat(h.variableColumns()).containsEntry(0, "a").containsEntry(3, "b2");
        // BOM and surrounding spaces are tolerated
        var bom = CsvRowValidator.validateHeader(List.of((char) 0xFEFF + "externalRef", " var_x "), 51);
        assertThat(bom.refIndex()).isZero();
        assertThat(bom.variableColumns()).containsEntry(1, "x");
        // locale is optional
        assertThat(CsvRowValidator.validateHeader(List.of("externalRef"), 51).localeIndex()).isEqualTo(-1);
    }

    @Test
    void invalidHeaders_areRejected() {
        assertInvalidHeader(List.of("locale", "var_a"), "externalRef");      // required column missing
        assertInvalidHeader(List.of("externalRef", "externalRef"), "duplicate");
        assertInvalidHeader(List.of("externalRef", "var_a", "var_A"), "duplicate"); // case-insensitive duplicate
        assertInvalidHeader(List.of("externalRef", "email"), "unsupported");  // no way to smuggle an address column
        assertInvalidHeader(List.of("externalRef", "var_"), "unsupported");
        assertInvalidHeader(List.of("externalRef", "var_1a"), "unsupported");
        assertInvalidHeader(List.of("externalRef", "var_a b"), "unsupported");
        assertInvalidHeader(List.of("externalRef", ""), "no name");
        List<String> many = new ArrayList<>(List.of("externalRef"));
        for (int i = 0; i < 51; i++) {
            many.add("var_c" + i);
        }
        assertInvalidHeader(many, "more than");
    }

    private static void assertInvalidHeader(List<String> cols, String hint) {
        assertThatThrownBy(() -> CsvRowValidator.validateHeader(cols, 51)).isInstanceOf(BulkRowException.class)
                .hasMessageContaining("INVALID_HEADER").hasMessageContaining(hint);
    }

    @Test
    void headerErrorText_neverEchoesHostileColumnNames() {
        assertThatThrownBy(() -> CsvRowValidator.validateHeader(List.of("externalRef", "<script>alert(1)</script>"), 51))
                .hasMessageNotContaining("<script>");
    }

    // ------------------------------------------------------------------ line reader

    private static List<String> lines(String text, int max) throws IOException {
        try (BoundedLineReader r = new BoundedLineReader(new StringReader(text), max)) {
            List<String> out = new ArrayList<>();
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                out.add(l);
            }
            return out;
        }
    }

    @Test
    void lineReader_handlesAllTerminators_andKeepsBlankLines() throws IOException {
        assertThat(lines("a\nb\r\nc\rd", 100)).containsExactly("a", "b", "c", "d");
        assertThat(lines("a\n\nb\n", 100)).containsExactly("a", "", "b");
        assertThat(lines("a\r\n\r\nb", 100)).containsExactly("a", "", "b");
        assertThat(lines("", 100)).isEmpty();
        assertThat(lines("\n", 100)).containsExactly("");
        assertThat(lines("no terminator", 100)).containsExactly("no terminator");
    }

    @Test
    void lineReader_boundsLongLines_andResynchronises() throws IOException {
        String hostile = "x".repeat(10_000_000); // 10 M characters without a line break
        List<String> out = lines("ok\n" + hostile + "\nafter\r\n" + hostile, 50);
        assertThat(out).containsExactly("ok", BoundedLineReader.TOO_LONG, "after", BoundedLineReader.TOO_LONG);
        // exactly at the limit is fine, one over is not
        assertThat(lines("a".repeat(50) + "\n" + "a".repeat(51), 50))
                .containsExactly("a".repeat(50), BoundedLineReader.TOO_LONG);
    }

    // ------------------------------------------------------------------ streaming reader

    private Path lastFile;

    private FlatFileItemReader<RecipientRow> reader(String content, boolean saveState) throws Exception {
        Path f = dir.resolve("r-" + System.nanoTime() + ".csv");
        lastFile = f;
        Files.writeString(f, content, StandardCharsets.UTF_8);
        FlatFileItemReader<RecipientRow> r = RecipientCsvReaderFactory.create(f, 100, 51, saveState);
        r.afterPropertiesSet();
        return r;
    }

    private static List<RecipientRow> readAll(FlatFileItemReader<RecipientRow> r) throws Exception {
        r.open(new ExecutionContext());
        List<RecipientRow> rows = new ArrayList<>();
        for (RecipientRow x = r.read(); x != null; x = r.read()) {
            rows.add(x);
        }
        r.close();
        return rows;
    }

    @Test
    void reader_mapsHeaderDrivenColumns_withTrueLineNumbers() throws Exception {
        var rows = readAll(reader("externalRef,locale,var_a,var_b\nR1,en,x,y\n\nR2,,\"q,uoted\",z\n", false));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).lineNo()).isEqualTo(2);
        assertThat(rows.get(0).externalRef()).isEqualTo("R1");
        assertThat(rows.get(0).variables()).containsEntry("a", "x").containsEntry("b", "y");
        // a blank line is a row error that keeps its own line number (so later numbers stay true)
        assertThat(rows.get(1).lineNo()).isEqualTo(3);
        assertThat(rows.get(1).parseError()).isEqualTo("BLANK_LINE");
        assertThat(rows.get(2).lineNo()).isEqualTo(4);
        assertThat(rows.get(2).locale()).isEmpty();
        assertThat(rows.get(2).variables()).containsEntry("a", "q,uoted");
    }

    @Test
    void reader_reportsStructuralProblemsPerRow_withoutThrowing() throws Exception {
        String longLine = "R9,en," + "z".repeat(200) + ",y";
        var rows = readAll(reader("externalRef,locale,var_a,var_b\nR1,en,x\nR2,en,x,y,extra\n" + longLine + "\n#R3,en,x,y\n",
                false));
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).parseError()).startsWith("WRONG_COLUMN_COUNT: expected 4, found 3");
        assertThat(rows.get(1).parseError()).startsWith("WRONG_COLUMN_COUNT: expected 4, found 5");
        assertThat(rows.get(2).parseError()).isEqualTo("LINE_TOO_LONG");
        // '#' is not a comment marker: such a line is data (and later fails the reference rule)
        assertThat(rows.get(3).parseError()).isNull();
        assertThat(rows.get(3).externalRef()).isEqualTo("#R3");
    }

    @Test
    void reader_failsTheStepOnABadHeader_orInvalidUtf8() throws Exception {
        FlatFileItemReader<RecipientRow> bad = reader("foo,bar\n1,2\n", false);
        assertThatThrownBy(() -> bad.open(new ExecutionContext())).hasRootCauseInstanceOf(BulkRowException.class);

        Path f = dir.resolve("latin1.csv");
        Files.write(f, "externalRef,var_a\nR1,Zoé\n".getBytes(StandardCharsets.ISO_8859_1));
        FlatFileItemReader<RecipientRow> r = RecipientCsvReaderFactory.create(f, 100, 51, false);
        r.afterPropertiesSet();
        assertThatThrownBy(() -> {
            r.open(new ExecutionContext());
            while (r.read() != null) {
                // drain
            }
        }).hasRootCauseInstanceOf(MalformedInputException.class);
        r.close();
    }

    @Test
    void reader_withCheckpointing_resumesAtTheSavedPosition_evenAcrossBadRows() throws Exception {
        String content = "externalRef,var_a\nR1,a\nBAD\nR3,c\nR4,d\nR5,e\n";
        FlatFileItemReader<RecipientRow> first = reader(content, true);
        ExecutionContext ctx = new ExecutionContext();
        first.open(ctx);
        assertThat(first.read().externalRef()).isEqualTo("R1");
        assertThat(first.read().parseError()).startsWith("WRONG_COLUMN_COUNT"); // line 3, no exception
        first.update(ctx);
        first.close();

        // restart: fast-forwarding over the bad row must not fail, and reading continues with line 4
        FlatFileItemReader<RecipientRow> second = RecipientCsvReaderFactory.create(lastFile, 100, 51, true);
        second.afterPropertiesSet();
        second.open(ctx);
        RecipientRow next = second.read();
        assertThat(next.externalRef()).isEqualTo("R3");
        assertThat(next.lineNo()).isEqualTo(4);
        second.close();
    }
}
