package com.example.messaging.web.dto;

import com.example.messaging.common.ApiError;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.TemplateStatus;
import com.example.messaging.template.RenderedContent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TemplateDtos {

    private TemplateDtos() {}

    public static final String CODE_PATTERN = "[A-Za-z0-9_.-]{1,100}";
    public static final String LOCALE_PATTERN = "[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8}){0,2}";

    public record CreateTemplateRequest(
            @NotBlank @Pattern(regexp = CODE_PATTERN) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull Channel channel,
            @NotNull Category category,
            @NotBlank @Pattern(regexp = LOCALE_PATTERN) String locale,
            @Size(max = 500) String subject,
            @NotBlank @Size(max = 10000) String body,
            @Size(max = 50) List<String> variables) {}

    public record CreateVersionRequest(
            Integer versionNo,
            @NotBlank @Pattern(regexp = LOCALE_PATTERN) String locale,
            @Size(max = 500) String subject,
            @NotBlank @Size(max = 10000) String body,
            @Size(max = 50) List<String> variables) {}

    /** {@code version} is the optimistic-lock token of the version row being edited. */
    public record UpdateVersionRequest(
            @Size(max = 500) String subject,
            @NotBlank @Size(max = 10000) String body,
            @Size(max = 50) List<String> variables,
            @NotNull Long version) {}

    public record RejectRequest(@NotBlank @Size(max = 1000) String comment) {}

    public record PreviewRequest(
            @NotNull Channel channel,
            @Size(max = 500) String subject,
            @NotBlank @Size(max = 10000) String body,
            @Size(max = 50) List<String> variables,
            @Size(max = 50) Map<String, String> sampleData,
            @Size(max = 20) String locale) {}

    public record PreviewResponse(
            boolean valid,
            String subject,
            String body,
            boolean html,
            RenderedContent.SmsInfo sms,
            List<ApiError.Detail> errors) {}

    public record VersionView(
            String id,
            int versionNo,
            String locale,
            TemplateStatus status,
            String subject,
            String body,
            List<String> variables,
            String createdBy,
            String approvedBy,
            Instant publishedAt,
            String rejectionComment,
            Instant createdAt,
            Instant updatedAt,
            Long version) {}

    public record TemplateSummary(
            String id,
            String tenantId,
            String code,
            String name,
            Channel channel,
            Category category,
            int latestVersionNo,
            List<String> publishedLocales,
            Map<TemplateStatus, Long> versionCounts,
            Instant createdAt,
            Instant updatedAt) {}

    public record TemplateDetail(
            String id,
            String tenantId,
            String code,
            String name,
            Channel channel,
            Category category,
            Instant createdAt,
            Instant updatedAt,
            List<VersionView> versions) {}
}
