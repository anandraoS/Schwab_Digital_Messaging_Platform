package com.example.messaging.template;

import com.example.messaging.common.ApiError;
import com.example.messaging.config.MessagingProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Validates variable names (declared and supplied), counts and value limits. */
@Component
public class VariableValidator {

    public static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final MessagingProperties.Templates limits;

    public VariableValidator(MessagingProperties props) {
        this.limits = props.templates();
    }

    /** Declared variable names of a template must be unique identifiers. */
    public void validateDeclared(Collection<String> declared) {
        List<ApiError.Detail> problems = new ArrayList<>();
        if (declared.size() > limits.maxVariables()) {
            problems.add(new ApiError.Detail("variables", "at most " + limits.maxVariables() + " variables are allowed"));
        }
        for (String n : declared) {
            if (n == null || !NAME.matcher(n).matches()) {
                problems.add(new ApiError.Detail("variables", "invalid variable name '" + safe(n) + "'"));
            }
        }
        if (!problems.isEmpty()) {
            throw new TemplateRenderException(com.example.messaging.common.ErrorCode.TEMPLATE_RENDER_ERROR,
                    "Template variable declaration is invalid", problems);
        }
    }

    /** Supplied variables must be a subset of the declared ones and respect size limits. */
    public void validateSupplied(Set<String> declared, Map<String, String> supplied) {
        List<ApiError.Detail> problems = new ArrayList<>();
        if (supplied.size() > limits.maxVariables()) {
            problems.add(new ApiError.Detail("variables", "at most " + limits.maxVariables() + " variables are allowed"));
        }
        supplied.forEach((k, v) -> {
            if (k == null || !NAME.matcher(k).matches()) {
                problems.add(new ApiError.Detail("variables", "invalid variable name '" + safe(k) + "'"));
            } else if (!declared.contains(k)) {
                problems.add(new ApiError.Detail("variables." + k, "variable is not declared by the template"));
            }
            if (v != null && v.length() > limits.maxVariableLength()) {
                problems.add(new ApiError.Detail("variables." + safe(k),
                        "value longer than " + limits.maxVariableLength() + " characters"));
            }
            if (v != null && hasForbiddenControlChars(v)) {
                problems.add(new ApiError.Detail("variables." + safe(k), "value contains control characters"));
            }
        });
        if (!problems.isEmpty()) {
            throw new TemplateRenderException(com.example.messaging.common.ErrorCode.TEMPLATE_RENDER_ERROR,
                    "Template variables are invalid", problems);
        }
    }

    static boolean hasForbiddenControlChars(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                return true;
            }
            if (c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.length() > 40 ? s.substring(0, 40) + "..." : s;
        return t.replaceAll("[\\p{Cntrl}]", "?");
    }
}
