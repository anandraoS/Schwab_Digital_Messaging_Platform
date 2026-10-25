package com.example.messaging.common;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Log/response redaction utilities. Message content and full addresses never appear in logs. */
public final class Redaction {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "token", "authorization", "apikey", "api_key", "otp", "pin",
            "ssn", "cvv", "accountnumber", "email", "phone", "pushtoken", "signature");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\+?\\d[\\d\\s-]{8,}\\d");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    public static final String MASK = "***";

    private Redaction() {}

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return MASK;
        }
        return email.charAt(0) + MASK + email.substring(at);
    }

    /** {@code +15550100101} becomes {@code +1555***0101}. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        if (phone.length() <= 8) {
            return MASK;
        }
        return phone.substring(0, 5) + MASK + phone.substring(phone.length() - 4);
    }

    public static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return token.length() <= 8 ? MASK : token.substring(0, 4) + MASK;
    }

    /** Masks anything in free text that looks like an email, long number or bearer token. */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String out = BEARER.matcher(text).replaceAll("Bearer " + MASK);
        out = EMAIL.matcher(out).replaceAll(m -> maskEmail(m.group()));
        out = LONG_DIGITS.matcher(out).replaceAll(MASK);
        return out;
    }

    /** True for keys whose value must never be stored or shown (passwords, tokens, addresses, signatures ...). */
    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    /** Masks only values whose key is sensitive; other values are untouched (safe for ids and timestamps). */
    public static Map<String, Object> redactKeys(Map<String, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        input.forEach((k, v) -> out.put(k, k != null && SENSITIVE_KEYS.contains(k.toLowerCase(Locale.ROOT)) ? MASK : v));
        return out;
    }

    /** Key-based masking plus pattern masking of free-text values (emails, long numbers, bearer tokens). */
    public static Map<String, Object> redactMap(Map<String, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        input.forEach((k, v) -> {
            if (k != null && SENSITIVE_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                out.put(k, MASK);
            } else if (v instanceof String s) {
                out.put(k, redact(s));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }
}
