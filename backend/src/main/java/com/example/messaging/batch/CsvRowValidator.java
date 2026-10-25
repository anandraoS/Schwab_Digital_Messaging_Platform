package com.example.messaging.batch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure CSV rules shared by the upload scan, the reader and the processor.
 *
 * <ul>
 *   <li>header: {@code externalRef} required, optional {@code locale}, every other column {@code var_<name>}</li>
 *   <li>cell values: no control characters, bounded length, CSV/formula-injection guard (a cell must not start with
 *       {@code = @ TAB CR}; a leading {@code + -} is accepted only for plain numbers such as {@code -12.50})</li>
 * </ul>
 */
public final class CsvRowValidator {

    public static final String EXTERNAL_REF = "externalRef";
    public static final String LOCALE = "locale";
    public static final String VAR_PREFIX = "var_";
    /** Longest external reference: idempotency key {@code bulk-<36 chars>-<ref>} must stay within 128 characters. */
    public static final int MAX_EXTERNAL_REF = 80;

    private static final char BOM = (char) 0xFEFF;
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (MAX_EXTERNAL_REF - 1) + "}");
    private static final Pattern LOCALE_PATTERN = Pattern.compile("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})?");
    private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?[0-9]+([.,][0-9]+)?");

    private CsvRowValidator() {}

    /** Validated header: column names in file order. */
    public record Header(List<String> columns, int refIndex, int localeIndex, Map<Integer, String> variableColumns) {}

    /** Parses and validates the header line (already split on commas by the tokenizer). */
    public static Header validateHeader(List<String> rawColumns, int maxColumns) {
        if (rawColumns.size() > maxColumns) {
            throw new BulkRowException("INVALID_HEADER", "more than " + maxColumns + " columns");
        }
        List<String> cols = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int ref = -1;
        int locale = -1;
        Map<Integer, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < rawColumns.size(); i++) {
            String c = stripBom(rawColumns.get(i)).trim();
            if (c.isEmpty()) {
                throw new BulkRowException("INVALID_HEADER", "column " + (i + 1) + " has no name");
            }
            if (!seen.add(c.toLowerCase(java.util.Locale.ROOT))) {
                throw new BulkRowException("INVALID_HEADER", "duplicate column '" + safe(c) + "'");
            }
            if (c.equals(EXTERNAL_REF)) {
                ref = i;
            } else if (c.equals(LOCALE)) {
                locale = i;
            } else if (c.startsWith(VAR_PREFIX) && VAR_NAME.matcher(c.substring(VAR_PREFIX.length())).matches()) {
                vars.put(i, c.substring(VAR_PREFIX.length()));
            } else {
                throw new BulkRowException("INVALID_HEADER",
                        "unsupported column '" + safe(c) + "' (allowed: externalRef, locale, var_<name>)");
            }
            cols.add(c);
        }
        if (ref < 0) {
            throw new BulkRowException("INVALID_HEADER", "required column 'externalRef' is missing");
        }
        return new Header(List.copyOf(cols), ref, locale, Map.copyOf(vars));
    }

    /** Header names are not PII, but keep error text bounded and printable. */
    private static String safe(String s) {
        String t = s.length() > 40 ? s.substring(0, 40) : s;
        return t.replaceAll("[^A-Za-z0-9_.:-]", "?");
    }

    public static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == BOM ? s.substring(1) : s;
    }

    public static String validateExternalRef(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new BulkRowException("MISSING_EXTERNAL_REF");
        }
        String r = ref.trim();
        if (!REF.matcher(r).matches()) {
            throw new BulkRowException("INVALID_EXTERNAL_REF", "allowed: letters, digits, . _ : - (max "
                    + MAX_EXTERNAL_REF + ", must start with a letter or digit)");
        }
        return r;
    }

    /** @return normalised locale ({@code _} to {@code -}) or {@code null} when blank */
    public static String validateLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return null;
        }
        String l = locale.trim();
        if (!LOCALE_PATTERN.matcher(l).matches()) {
            throw new BulkRowException("INVALID_LOCALE");
        }
        return l.replace('_', '-');
    }

    /**
     * Validates one variable cell. {@code name} is only used in the (PII-free) error hint.
     *
     * @return the trimmed value
     */
    public static String validateValue(String name, String value, int maxLength) {
        String v = value == null ? "" : value.trim();
        if (v.length() > maxLength) {
            throw new BulkRowException("FIELD_TOO_LONG", "variable '" + name + "' exceeds " + maxLength + " characters");
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0)) {
                throw new BulkRowException("CONTROL_CHARACTER", "variable '" + name + "'");
            }
        }
        if (!v.isEmpty() && isFormulaStart(v)) {
            throw new BulkRowException("FORMULA_INJECTION", "variable '" + name + "' starts with a formula character");
        }
        return v;
    }

    static boolean isFormulaStart(String v) {
        char first = v.charAt(0);
        if (first == '=' || first == '@' || first == '\t' || first == '\r') {
            return true;
        }
        if (first == '+' || first == '-') {
            return !PLAIN_NUMBER.matcher(v).matches();
        }
        return false;
    }
}
