package com.example.messaging.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.messaging.TestProps;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.domain.Channel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer(TestProps.defaults());
    private final VariableValidator validator = new VariableValidator(TestProps.defaults());
    private static final Set<String> DECLARED = Set.of("name", "amount", "a", "x");

    private String email(String body, Map<String, String> vars) {
        return renderer.render(Channel.EMAIL, "Subject", body, DECLARED, vars).body();
    }

    @Test
    void substitutesDeclaredVariablesAndKeepsLiteralText() {
        assertThat(email("Hello {{name}}, you paid {{amount}}.", Map.of("name", "Ada", "amount", "5.00")))
                .isEqualTo("Hello Ada, you paid 5.00.");
        assertThat(email("Hi {{ name }}!", Map.of("name", "Ada"))).isEqualTo("Hi Ada!");
    }

    @Test
    void defaultsApplyOnlyWhenTheVariableIsAbsent() {
        assertThat(email("Hi {{name|Customer}}", Map.of())).isEqualTo("Hi Customer");
        assertThat(email("Hi {{name|Customer}}", Map.of("name", "Ada"))).isEqualTo("Hi Ada");
        assertThat(email("Hi {{name|}}", Map.of())).isEqualTo("Hi ");
    }

    @Test
    void emailBodyValuesAreHtmlEscaped() {
        String out = email("<p>{{name}}</p>", Map.of("name", "<script>alert('x')</script>&\"`"));
        assertThat(out).doesNotContain("<script>");
        assertThat(out).isEqualTo("<p>&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;&quot;&#96;</p>");
    }

    @Test
    void htmlInTheTemplateItselfIsTrustedAuthorContentAndKept() {
        assertThat(email("<b>{{name}}</b>", Map.of("name", "Ada"))).isEqualTo("<b>Ada</b>");
    }

    @Test
    void defaultValuesAreEscapedToo() {
        assertThat(email("{{name|<i>friend</i>}}", Map.of())).isEqualTo("&lt;i&gt;friend&lt;/i&gt;");
    }

    @Test
    void smsAndPushAreNotHtmlEscaped() {
        RenderedContent sms = renderer.render(Channel.SMS, null, "Code {{name}}", DECLARED, Map.of("name", "<1&2>"));
        assertThat(sms.body()).isEqualTo("Code <1&2>");
        assertThat(sms.html()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{ 7*7 }}", "{{7*7}}", "{{name.length()}}", "{{a.b}}", "{{ name + amount }}",
            "{{name ; drop}}", "{{#if name}}", "{{{name}}}", "{{{{a}}}}", "{{}}", "{{ }}", "{{1name}}",
            "{{name|a|b}}", "{{T(java.lang.Runtime).getRuntime()}}", "{{name", "{{name}", "{{'x'}}",
            "{{\"x\"}}", "{{@x}}", "{{x/0}}"})
    void expressionsAndMalformedPlaceholdersAreRejectedNeverEvaluated(String template) {
        assertThatThrownBy(() -> renderer.render(Channel.EMAIL, "s", template, DECLARED,
                Map.of("name", "n", "amount", "1", "a", "a", "x", "x")))
                .isInstanceOf(TemplateRenderException.class)
                .satisfies(e -> assertThat(((TemplateRenderException) e).getCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_ERROR));
    }

    @Test
    void dollarBraceAndOtherEngineSyntaxIsPlainText() {
        assertThat(email("Total ${1+1} #{2+2} <%= 3 %> %s", Map.of())).isEqualTo("Total ${1+1} #{2+2} <%= 3 %> %s");
        assertThat(email("}} stray {", Map.of())).isEqualTo("}} stray {");
    }

    @Test
    void valuesAreNeverReinterpretedAsPlaceholders() {
        // a value containing "{{amount}}" must not be expanded a second time
        assertThat(email("Hi {{name}} {{amount}}", Map.of("name", "{{amount}}", "amount", "9")))
                .isEqualTo("Hi {{amount}} 9");
        assertThat(email("Hi {{name}}", Map.of("name", "${7*7}"))).isEqualTo("Hi ${7*7}");
    }

    @Test
    void missingVariablesAreListedTogether() {
        assertThatThrownBy(() -> email("{{name}} {{amount}} {{name}}", Map.of()))
                .isInstanceOf(TemplateRenderException.class)
                .satisfies(e -> {
                    TemplateRenderException t = (TemplateRenderException) e;
                    assertThat(t.getCode()).isEqualTo(ErrorCode.TEMPLATE_VARIABLE_MISSING);
                    assertThat(t.getDetails()).extracting("field").containsExactlyInAnyOrder("variables.name", "variables.amount");
                });
    }

    @Test
    void validateReportsUndeclaredPlaceholdersWithDetails() {
        assertThatThrownBy(() -> renderer.validate(Channel.EMAIL, "s", "Hello {{secret}} {{name}}", DECLARED))
                .isInstanceOf(TemplateRenderException.class)
                .satisfies(e -> assertThat(((TemplateRenderException) e).getDetails().toString())
                        .contains("secret").doesNotContain("'name'"));
        assertThat(renderer.validate(Channel.EMAIL, "s", "Hello {{name}} {{amount|1}}", DECLARED))
                .containsExactlyInAnyOrder("name", "amount");
    }

    @Test
    void suppliedUnknownVariablesAreRejectedByTheValidator() {
        assertThatThrownBy(() -> validator.validateSupplied(Set.of("name"), Map.of("name", "n", "evil", "x")))
                .isInstanceOf(TemplateRenderException.class)
                .satisfies(e -> assertThat(((TemplateRenderException) e).getDetails().toString()).contains("variables.evil"));
        validator.validateSupplied(Set.of("name"), Map.of("name", "n"));
    }

    @Test
    void validatorEnforcesNamesLengthsAndControlCharacters() {
        assertThatThrownBy(() -> validator.validateDeclared(List.of("ok", "bad name")))
                .isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> validator.validateDeclared(List.of("1abc"))).isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> validator.validateSupplied(Set.of("name"), Map.of("name", "x".repeat(2001))))
                .isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> validator.validateSupplied(Set.of("name"), Map.of("name", "a\u0000b")))
                .isInstanceOf(TemplateRenderException.class);
        validator.validateSupplied(Set.of("name"), Map.of("name", "line1\nline2\tok"));
        java.util.Map<String, String> many = new java.util.HashMap<>();
        for (int i = 0; i < 51; i++) {
            many.put("v" + i, "x");
        }
        assertThatThrownBy(() -> validator.validateSupplied(many.keySet(), many)).isInstanceOf(TemplateRenderException.class);
    }

    @Test
    void subjectLineBreaksAreStrippedToPreventHeaderInjection() {
        RenderedContent c = renderer.render(Channel.EMAIL, "Hi {{name}}", "body", DECLARED,
                Map.of("name", "Ada\r\nBcc: attacker@example.test"));
        assertThat(c.subject()).doesNotContain("\r").doesNotContain("\n");
        assertThat(c.subject()).startsWith("Hi Ada");
    }

    @Test
    void channelSpecificRulesAreEnforced() {
        assertThatThrownBy(() -> renderer.validate(Channel.EMAIL, " ", "b", DECLARED)).isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> renderer.validate(Channel.PUSH, null, "b", DECLARED)).isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> renderer.validate(Channel.SMS, "should not be here", "b", DECLARED))
                .isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> renderer.validate(Channel.SMS, null, "", DECLARED)).isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> renderer.validate(Channel.SMS, null, "x".repeat(10001), DECLARED))
                .isInstanceOf(TemplateRenderException.class);
    }

    @Test
    void smsSegmentCountingGsmVersusUcs2() {
        assertThat(TemplateRenderer.smsInfo("a".repeat(160))).isEqualTo(new RenderedContent.SmsInfo("GSM-7", 160, 1));
        assertThat(TemplateRenderer.smsInfo("a".repeat(161))).isEqualTo(new RenderedContent.SmsInfo("GSM-7", 161, 2));
        assertThat(TemplateRenderer.smsInfo("a".repeat(306)).segments()).isEqualTo(2);
        assertThat(TemplateRenderer.smsInfo("a".repeat(307)).segments()).isEqualTo(3);
        // extension characters cost two septets: 80 euro signs = 160 septets = 1 segment, 81 = 162 = 2 segments
        assertThat(TemplateRenderer.smsInfo("€".repeat(80)).segments()).isEqualTo(1);
        assertThat(TemplateRenderer.smsInfo("€".repeat(81)).segments()).isEqualTo(2);
        // one non-GSM character switches the whole message to UCS-2 (70 / 67)
        RenderedContent.SmsInfo ucs = TemplateRenderer.smsInfo("a".repeat(70) + "中");
        assertThat(ucs.encoding()).isEqualTo("UCS-2");
        assertThat(ucs.segments()).isEqualTo(2);
        assertThat(TemplateRenderer.smsInfo("中".repeat(70)).segments()).isEqualTo(1);
        assertThat(TemplateRenderer.smsInfo("中".repeat(134)).segments()).isEqualTo(2);
        assertThat(TemplateRenderer.smsInfo("中".repeat(135)).segments()).isEqualTo(3);
        assertThat(TemplateRenderer.smsInfo("hi 😀").encoding()).isEqualTo("UCS-2");
        assertThat(TemplateRenderer.smsInfo("").segments()).isEqualTo(1);
    }

    @Test
    void renderedSmsOverTheSegmentLimitIsRejected() {
        String body = "{{name}}";
        assertThat(renderer.render(Channel.SMS, null, body, DECLARED, Map.of("name", "a".repeat(1000))).sms().segments())
                .isEqualTo(7);
        assertThatThrownBy(() -> renderer.render(Channel.SMS, null, body, DECLARED, Map.of("name", "a".repeat(1600))))
                .isInstanceOf(TemplateRenderException.class);
    }

    @Test
    void pushTitleAndBodyLimitsApplyToTheRenderedOutput() {
        assertThat(renderer.render(Channel.PUSH, "T {{name}}", "B", DECLARED, Map.of("name", "n")).subject()).isEqualTo("T n");
        assertThatThrownBy(() -> renderer.render(Channel.PUSH, "T {{name}}", "B", DECLARED, Map.of("name", "n".repeat(70))))
                .isInstanceOf(TemplateRenderException.class);
        assertThatThrownBy(() -> renderer.render(Channel.PUSH, "T", "B {{name}}", DECLARED, Map.of("name", "n".repeat(250))))
                .isInstanceOf(TemplateRenderException.class);
    }

    @Test
    void renderedContentToStringDoesNotLeakTheBody() {
        RenderedContent c = renderer.render(Channel.EMAIL, "s", "super secret {{name}}", DECLARED, Map.of("name", "Ada"));
        assertThat(c.toString()).doesNotContain("super secret").doesNotContain("Ada");
    }
}
