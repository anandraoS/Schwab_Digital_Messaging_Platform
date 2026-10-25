package com.example.messaging.batch;

import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.separator.SimpleRecordSeparatorPolicy;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.item.file.transform.IncorrectTokenCountException;
import org.springframework.core.io.FileSystemResource;

/**
 * Builds the streaming, header-driven CSV reader.
 *
 * <ul>
 *   <li>strictly UTF-8: malformed bytes fail the step instead of being silently replaced</li>
 *   <li>never buffers more than {@code maxLineLength} characters of one line ({@link BoundedLineReader})</li>
 *   <li>strict tokenisation: a row whose column count differs from the header is reported as a row error</li>
 *   <li>no comment lines, no multi-line quoted records (one record per line, so line numbers are stable)</li>
 *   <li>the mapper is pure and never throws for bad content, so restart fast-forwarding cannot fail on bad rows</li>
 * </ul>
 */
public final class RecipientCsvReaderFactory {

    private RecipientCsvReaderFactory() {}

    public static FlatFileItemReader<RecipientRow> create(Path file, int maxLineLength, int maxColumns,
            boolean saveState) {
        RowMapper mapper = new RowMapper(maxColumns);

        FlatFileItemReader<RecipientRow> reader = new FlatFileItemReader<>();
        reader.setName("recipientCsvReader");
        reader.setResource(new FileSystemResource(file));
        reader.setStrict(true);
        reader.setEncoding(StandardCharsets.UTF_8.name());
        reader.setComments(new String[0]);
        reader.setLinesToSkip(1);
        reader.setSkippedLinesCallback(mapper::acceptHeader);
        reader.setRecordSeparatorPolicy(new SimpleRecordSeparatorPolicy());
        reader.setBufferedReaderFactory((resource, encoding) -> {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return new BoundedLineReader(new InputStreamReader(resource.getInputStream(), decoder), maxLineLength);
        });
        reader.setLineMapper(mapper);
        reader.setSaveState(saveState);
        return reader;
    }

    static DelimitedLineTokenizer newTokenizer() {
        DelimitedLineTokenizer t = new DelimitedLineTokenizer(DelimitedLineTokenizer.DELIMITER_COMMA);
        t.setQuoteCharacter(DelimitedLineTokenizer.DEFAULT_QUOTE_CHARACTER);
        t.setStrict(true);
        return t;
    }

    /** Header-driven line mapper. {@link #acceptHeader} runs on every (re)open, before any row is mapped. */
    static final class RowMapper implements LineMapper<RecipientRow> {

        private final int maxColumns;
        private final DelimitedLineTokenizer rowTokenizer = newTokenizer();
        private volatile CsvRowValidator.Header header;

        RowMapper(int maxColumns) {
            this.maxColumns = maxColumns;
        }

        void acceptHeader(String line) {
            if (BoundedLineReader.TOO_LONG.equals(line)) {
                throw new BulkRowException("INVALID_HEADER", "header line is too long");
            }
            DelimitedLineTokenizer ht = newTokenizer();
            ht.setStrict(false);
            FieldSet fs = ht.tokenize(CsvRowValidator.stripBom(line));
            List<String> cols = new ArrayList<>();
            for (String v : fs.getValues()) {
                cols.add(v);
            }
            CsvRowValidator.Header h = CsvRowValidator.validateHeader(cols, maxColumns);
            rowTokenizer.setNames(h.columns().toArray(new String[0]));
            this.header = h;
        }

        @Override
        public RecipientRow mapLine(String line, int lineNumber) {
            if (BoundedLineReader.TOO_LONG.equals(line)) {
                return RecipientRow.broken(lineNumber, line, "LINE_TOO_LONG");
            }
            if (line.isBlank()) {
                return RecipientRow.broken(lineNumber, line, "BLANK_LINE");
            }
            CsvRowValidator.Header h = header;
            if (h == null) {
                throw new IllegalStateException("CSV header was not read before the first row");
            }
            try {
                String[] v = rowTokenizer.tokenize(line).getValues();
                Map<String, String> vars = new LinkedHashMap<>();
                h.variableColumns().forEach((idx, name) -> vars.put(name, v[idx]));
                String locale = h.localeIndex() >= 0 ? v[h.localeIndex()] : null;
                return new RecipientRow(lineNumber, v[h.refIndex()], locale, vars, line, null);
            } catch (IncorrectTokenCountException e) {
                return RecipientRow.broken(lineNumber, line,
                        "WRONG_COLUMN_COUNT: expected " + e.getExpectedCount() + ", found " + e.getActualCount());
            } catch (RuntimeException e) {
                return RecipientRow.broken(lineNumber, line, "MALFORMED_ROW");
            }
        }
    }
}
