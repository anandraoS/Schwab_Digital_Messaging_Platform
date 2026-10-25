package com.example.messaging.template;

import com.example.messaging.common.ApiError;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.domain.Channel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Safe template engine. Grammar: {@code {{name}}} or {@code {{name|default}}}; whitespace around the name is allowed.
 * There is no expression evaluation of any kind: anything that is not a plain identifier inside the braces is a
 * template error ({@code {{ 7*7 }}}, {@code {{a.b}}}, {@code {{{x}}}}). Text such as {@code ${...}} is literal.
 * Only variables declared by the template are accepted. For the EMAIL channel values (and defaults) are HTML-escaped
 * in the body; CR/LF are always stripped from subjects/titles to prevent header injection.
 */
@Component
public class TemplateRenderer {

    private static final int MAX_DEFAULT_LENGTH = 200;
    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅå"
            + "Δ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENDED = "\f^{}\\[~]|€";

    private sealed interface Part permits Literal, Var {}

    private record Literal(String text) implements Part {}

    private record Var(String name, String defaultValue) implements Part {}

    private final MessagingProperties.Templates limits;

    public TemplateRenderer(MessagingProperties props) {
        this.limits = props.templates();
    }

    // ---------------------------------------------------------------- validation (authoring time)

    /**
     * Static validation of a template: syntax, every placeholder declared, per-channel subject/body rules and size
     * limits. Throws {@link TemplateRenderException} with details; returns the placeholders used.
     */
    public Set<String> validate(Channel channel, String subject, String body, Set<String> declared) {
        List<ApiError.Detail> problems = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        if (body == null || body.isBlank()) {
            problems.add(new ApiError.Detail("body", "body is required"));
        } else if (body.length() > limits.maxBodyLength()) {
            problems.add(new ApiError.Detail("body", "body longer than " + limits.maxBodyLength() + " characters"));
        }
        switch (channel) {
            case EMAIL -> {
                if (subject == null || subject.isBlank()) {
                    problems.add(new ApiError.Detail("subject", "subject is required for EMAIL"));
                }
            }
            case PUSH -> {
                if (subject == null || subject.isBlank()) {
                    problems.add(new ApiError.Detail("subject", "title (subject) is required for PUSH"));
                }
            }
            case SMS -> {
                if (subject != null && !subject.isBlank()) {
                    problems.add(new ApiError.Detail("subject", "subject must be empty for SMS"));
                }
            }
            default -> throw new IllegalStateException("Unhandled channel " + channel);
        }
        if (subject != null && subject.length() > limits.maxSubjectLength()) {
            problems.add(new ApiError.Detail("subject", "subject longer than " + limits.maxSubjectLength() + " characters"));
        }
        collect("subject", subject, declared, used, problems);
        collect("body", body, declared, used, problems);
        if (!problems.isEmpty()) {
            throw new TemplateRenderException(ErrorCode.TEMPLATE_RENDER_ERROR, "Template is invalid", problems);
        }
        return used;
    }

    private void collect(String field, String text, Set<String> declared, Set<String> used, List<ApiError.Detail> problems) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            for (Part p : parse(text, field)) {
                if (p instanceof Var v) {
                    used.add(v.name());
                    if (!declared.contains(v.name())) {
                        problems.add(new ApiError.Detail(field, "placeholder '" + v.name() + "' is not a declared variable"));
                    }
                }
            }
        } catch (TemplateRenderException e) {
            problems.addAll(e.getDetails());
        }
    }

    // ---------------------------------------------------------------- rendering (dispatch / preview time)

    public RenderedContent render(Channel channel, String subjectTemplate, String bodyTemplate,
            Set<String> declared, Map<String, String> variables) {
        validate(channel, subjectTemplate, bodyTemplate, declared);
        List<String> missing = new ArrayList<>();
        boolean html = channel == Channel.EMAIL;
        String subject = subjectTemplate == null || subjectTemplate.isBlank() ? null
                : stripLineBreaks(expand(parse(subjectTemplate, "subject"), variables, false, missing));
        String body = expand(parse(bodyTemplate, "body"), variables, html, missing);
        if (!missing.isEmpty()) {
            throw TemplateRenderException.missing(missing.stream().distinct().toList());
        }
        RenderedContent.SmsInfo sms = null;
        switch (channel) {
            case EMAIL -> {
                if (body.length() > limits.maxRenderedEmailLength()) {
                    throw TemplateRenderException.render("rendered email body is too large", "body");
                }
            }
            case SMS -> {
                sms = smsInfo(body);
                if (sms.segments() > limits.maxSmsSegments()) {
                    throw TemplateRenderException.render(
                            "rendered SMS needs " + sms.segments() + " segments (limit " + limits.maxSmsSegments() + ")", "body");
                }
            }
            case PUSH -> {
                if (subject != null && subject.length() > limits.maxPushTitleLength()) {
                    throw TemplateRenderException.render("rendered push title longer than " + limits.maxPushTitleLength(), "subject");
                }
                if (body.length() > limits.maxPushBodyLength()) {
                    throw TemplateRenderException.render("rendered push body longer than " + limits.maxPushBodyLength(), "body");
                }
            }
            default -> throw new IllegalStateException("Unhandled channel " + channel);
        }
        return new RenderedContent(subject, body, html, sms);
    }

    private String expand(List<Part> parts, Map<String, String> vars, boolean escapeHtml, List<String> missing) {
        StringBuilder out = new StringBuilder();
        for (Part p : parts) {
            if (p instanceof Literal l) {
                out.append(l.text());
            } else if (p instanceof Var v) {
                String val = vars == null ? null : vars.get(v.name());
                if (val == null) {
                    val = v.defaultValue();
                }
                if (val == null) {
                    missing.add(v.name());
                    continue;
                }
                out.append(escapeHtml ? escapeHtml(val) : val);
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- parsing

    private List<Part> parse(String text, String field) {
        List<Part> parts = new ArrayList<>();
        int i = 0;
        int n = text.length();
        StringBuilder lit = new StringBuilder();
        while (i < n) {
            int open = text.indexOf("{{", i);
            if (open < 0) {
                lit.append(text, i, n);
                break;
            }
            lit.append(text, i, open);
            int close = text.indexOf("}}", open + 2);
            if (close < 0) {
                throw TemplateRenderException.render("unterminated placeholder at position " + open, field);
            }
            String inner = text.substring(open + 2, close);
            if (inner.indexOf('{') >= 0 || inner.indexOf('}') >= 0) {
                throw TemplateRenderException.render("nested or malformed braces at position " + open, field);
            }
            if (lit.length() > 0) {
                parts.add(new Literal(lit.toString()));
                lit.setLength(0);
            }
            parts.add(parsePlaceholder(inner, open, field));
            i = close + 2;
        }
        if (lit.length() > 0) {
            parts.add(new Literal(lit.toString()));
        }
        return parts;
    }

    private Var parsePlaceholder(String inner, int position, String field) {
        String name = inner;
        String def = null;
        int bar = inner.indexOf('|');
        if (bar >= 0) {
            name = inner.substring(0, bar);
            def = inner.substring(bar + 1);
            if (def.indexOf('|') >= 0 || def.length() > MAX_DEFAULT_LENGTH || VariableValidator.hasForbiddenControlChars(def)) {
                throw TemplateRenderException.render("invalid default value at position " + position, field);
            }
        }
        name = name.trim();
        if (!VariableValidator.NAME.matcher(name).matches()) {
            throw TemplateRenderException.render(
                    "placeholder at position " + position + " must be a plain variable name (no expressions)", field);
        }
        return new Var(name, def);
    }

    // ---------------------------------------------------------------- helpers

    public static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                case '`' -> sb.append("&#96;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String stripLineBreaks(String s) {
        return s.replace("\r", " ").replace("\n", " ");
    }

    /** GSM-7 (160 / 153 per segment, extension chars cost 2) versus UCS-2 (70 / 67). */
    public static RenderedContent.SmsInfo smsInfo(String text) {
        boolean gsm = true;
        int septets = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (GSM_BASIC.indexOf(c) >= 0) {
                septets++;
            } else if (GSM_EXTENDED.indexOf(c) >= 0) {
                septets += 2;
            } else {
                gsm = false;
                break;
            }
        }
        if (gsm) {
            int segments = septets == 0 ? 1 : (septets <= 160 ? 1 : (int) Math.ceil(septets / 153.0));
            return new RenderedContent.SmsInfo("GSM-7", septets, segments);
        }
        int units = text.length();
        int segments = units == 0 ? 1 : (units <= 70 ? 1 : (int) Math.ceil(units / 67.0));
        return new RenderedContent.SmsInfo("UCS-2", units, segments);
    }
}
