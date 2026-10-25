package com.example.messaging.web.dto;

import com.example.messaging.domain.Channel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class RecipientDtos {

    private RecipientDtos() {}

    public record RecipientSummary(
            String id,
            String tenantId,
            String externalRef,
            String displayName,
            String maskedEmail,
            String maskedPhone,
            String locale,
            String timeZone,
            boolean deleted) {}

    /** {@code version} is null only for a recipient that has no stored preference row yet (defaults shown). */
    public record PreferencesResponse(
            String recipientId,
            boolean marketingOptIn,
            boolean emailEnabled,
            boolean smsEnabled,
            boolean pushEnabled,
            String quietStart,
            String quietEnd,
            String timeZone,
            List<Channel> fallbackOrder,
            List<Channel> suppressedChannels,
            Long version,
            Instant updatedAt) {}

    public record UpdatePreferencesRequest(
            @NotNull Boolean marketingOptIn,
            @NotNull Boolean emailEnabled,
            @NotNull Boolean smsEnabled,
            @NotNull Boolean pushEnabled,
            @Size(max = 5) String quietStart,
            @Size(max = 5) String quietEnd,
            @Size(max = 50) String timeZone,
            @Size(max = 3) List<Channel> fallbackOrder,
            Long version) {}

    public record SuppressionRequest(@NotNull Channel channel, @Size(max = 100) String reason) {}

    public record SuppressionResponse(String recipientId, Channel channel, String reason, Instant createdAt) {}
}
