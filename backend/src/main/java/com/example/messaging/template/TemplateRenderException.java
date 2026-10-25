package com.example.messaging.template;

import com.example.messaging.common.ApiError;
import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import java.util.List;

/** Template problem: TEMPLATE_RENDER_ERROR (syntax, unknown variable, limits) or TEMPLATE_VARIABLE_MISSING. */
public class TemplateRenderException extends ApiException {

    public TemplateRenderException(ErrorCode code, String message, List<ApiError.Detail> details) {
        super(code, message, details);
    }

    public static TemplateRenderException render(String message, String field) {
        return new TemplateRenderException(ErrorCode.TEMPLATE_RENDER_ERROR, message,
                List.of(new ApiError.Detail(field, message)));
    }

    public static TemplateRenderException missing(List<String> names) {
        return new TemplateRenderException(ErrorCode.TEMPLATE_VARIABLE_MISSING,
                "Missing required template variables: " + String.join(", ", names),
                names.stream().map(n -> new ApiError.Detail("variables." + n, "required variable is missing")).toList());
    }
}
