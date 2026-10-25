package com.example.messaging.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void masksEmailsPhonesAndTokens() {
        assertThat(Redaction.maskEmail("recipient001@example.test")).isEqualTo("r***@example.test");
        assertThat(Redaction.maskEmail("not-an-email")).isEqualTo("***");
        assertThat(Redaction.maskEmail(null)).isNull();
        assertThat(Redaction.maskPhone("+15550100101")).isEqualTo("+1555***0101");
        assertThat(Redaction.maskPhone("1234")).isEqualTo("***");
        assertThat(Redaction.maskToken("abcdef123456")).isEqualTo("abcd***");
    }

    @Test
    void redactsFreeText() {
        String out = Redaction.redact("mail jane.doe@example.test or call +1 555 010 0001 with Bearer abc.def.ghi");
        assertThat(out).doesNotContain("jane.doe").doesNotContain("555 010").doesNotContain("abc.def.ghi");
        assertThat(out).contains("j***@example.test");
    }

    @Test
    void redactKeysOnlyMasksSensitiveKeysAndKeepsIdsAndTimestamps() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("password", "hunter2");
        in.put("Authorization", "Bearer x");
        in.put("messageId", "123e4567-e89b-12d3-a456-426614174000");
        in.put("at", "2026-11-01T14:00:00Z");
        Map<String, Object> out = Redaction.redactKeys(in);
        assertThat(out.get("password")).isEqualTo("***");
        assertThat(out.get("Authorization")).isEqualTo("***");
        assertThat(out.get("messageId")).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(out.get("at")).isEqualTo("2026-11-01T14:00:00Z");
        assertThat(Redaction.redactMap(in).get("password")).isEqualTo("***");
    }
}
