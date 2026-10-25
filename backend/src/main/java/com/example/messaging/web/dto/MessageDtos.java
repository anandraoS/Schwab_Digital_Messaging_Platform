package com.example.messaging.web.dto;

import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.Priority;
import com.example.messaging.provider.ProviderOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request/response records for the message APIs. */
public final class MessageDtos {

    private MessageDtos() {}

    public record RecipientRef(@Size(max = 100) String externalRef) {}

    public record TemplateRef(@NotBlank @Size(max = 100) String code, @Size(max = 20) String locale, Integer version) {}

    public record EmailOptions(@Size(max = 320) String replyTo, @Size(max = 10) Map<String, String> headers) {}

    public record SmsOptions(@Size(max = 20) String senderId) {}

    public record PushOptions(@Size(max = 100) String collapseKey, @Min(1) @Max(2_419_200) Integer ttlSeconds) {}

    public record ChannelOptions(@Valid EmailOptions email, @Valid SmsOptions sms, @Valid PushOptions push) {}

    public record Schedule(Instant sendAt, Instant expiresAt) {}

    /** POST /api/v1/messages body. Exactly one of {@code recipientId} / {@code recipient.externalRef}. */
    public record SubmitMessageRequest(
            @Size(max = 200) String clientReference,
            @Size(max = 36) String recipientId,
            @Valid RecipientRef recipient,
            @NotNull Channel channel,
            @NotNull Category category,
            Priority priority,
            @NotNull @Valid TemplateRef template,
            @Size(max = 50) Map<String, String> variables,
            @Valid ChannelOptions channelOptions,
            @Size(max = 2) List<Channel> fallbackChannels,
            @Valid Schedule schedule,
            @Size(max = 20) Map<String, String> metadata) {

        public SubmitMessageRequest {
            priority = priority == null ? Priority.NORMAL : priority;
            variables = variables == null ? Map.of() : variables;
            fallbackChannels = fallbackChannels == null ? List.of() : fallbackChannels;
            metadata = metadata == null ? Map.of() : metadata;
        }
    }

    public record MessageAcceptedResponse(
            String id,
            MessageStatus status,
            Channel channel,
            String correlationId,
            Instant scheduledAt,
            String statusReason,
            Map<String, String> links) {}

    public record MessageSummary(
            String id,
            String clientReference,
            String recipientId,
            Channel channel,
            Category category,
            Priority priority,
            MessageStatus status,
            String statusReason,
            String correlationId,
            int attemptCount,
            Instant scheduledAt,
            Instant createdAt,
            Instant updatedAt,
            Channel channelUsed,
            String templateCode,
            Instant nextAttemptAt) {}

    public record RecipientView(String id, String externalRef, String maskedAddress) {}

    public record TemplateView(String code, int versionNo, String locale) {}

    public record AttemptView(
            int attemptNo,
            Channel channel,
            String providerId,
            ProviderOutcome outcome,
            String providerMessageId,
            String errorCode,
            Long latencyMs,
            Instant startedAt) {}

    public record DeliveryEventView(
            String id,
            String messageId,
            String providerId,
            String providerEventId,
            DeliveryEventType type,
            Instant occurredAt,
            Instant receivedAt,
            boolean applied,
            String ignoredReason) {}

    /** Never contains rendered content or full addresses. */
    public record MessageDetail(
            String id,
            String tenantId,
            String clientReference,
            RecipientView recipient,
            Channel channel,
            Channel channelUsed,
            Category category,
            Priority priority,
            MessageStatus status,
            String statusReason,
            boolean terminal,
            TemplateView template,
            String correlationId,
            String idempotencyKey,
            Instant scheduledAt,
            Instant expiresAt,
            int attemptCount,
            int maxAttempts,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            List<AttemptView> attempts,
            List<DeliveryEventView> deliveryEvents) {}

    public record MessageStatusResponse(
            String id,
            MessageStatus status,
            String statusReason,
            Channel channelUsed,
            int attemptCount,
            Instant updatedAt,
            boolean terminal) {}

    /** One row of the merged lifecycle timeline (audit + attempts + delivery events). */
    public record TimelineEntry(Instant at, String kind, String type, String summary, Map<String, Object> detail) {}

    public record DeadLetterItem(
            String messageId,
            String tenantId,
            Channel channel,
            Category category,
            Priority priority,
            DlqReason reason,
            String statusReason,
            int attemptCount,
            boolean discarded,
            Instant deadLetteredAt) {}

    public record DiscardRequest(@Size(max = 500) String reason) {}
}
