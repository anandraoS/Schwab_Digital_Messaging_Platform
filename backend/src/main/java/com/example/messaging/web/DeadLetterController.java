package com.example.messaging.web;

import com.example.messaging.common.PageResponse;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.Priority;
import com.example.messaging.service.DeadLetterOperations;
import com.example.messaging.service.NotificationService;
import com.example.messaging.web.dto.MessageDtos.DeadLetterItem;
import com.example.messaging.web.dto.MessageDtos.DiscardRequest;
import com.example.messaging.web.dto.MessageDtos.MessageStatusResponse;
import com.example.messaging.web.dto.MessageDtos.MessageSummary;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Operations")
public class DeadLetterController {

    private static final Set<String> SORTABLE_DLQ = Set.of("statusChangedAt", "createdAt", "priority", "channel");
    private static final Set<String> SORTABLE_FAILED = Set.of("statusChangedAt", "createdAt", "updatedAt", "status",
            "priority", "channel");
    private static final List<MessageStatus> INVESTIGATION = List.of(MessageStatus.FAILED,
            MessageStatus.DELIVERY_UNKNOWN, MessageStatus.RETRY_PENDING);

    private final NotificationService notifications;
    private final DeadLetterOperations deadLetters;

    public DeadLetterController(NotificationService notifications, DeadLetterOperations deadLetters) {
        this.notifications = notifications;
        this.deadLetters = deadLetters;
    }

    @GetMapping("/dead-letters")
    @PreAuthorize("hasAnyRole('OPERATOR','TENANT_ADMIN','PLATFORM_ADMIN')")
    public PageResponse<DeadLetterItem> deadLetters(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return PageResponse.of(notifications.listDeadLetters(
                Pagination.of(page, size, sort, SORTABLE_DLQ, "statusChangedAt", Sort.Direction.DESC)));
    }

    @PostMapping("/dead-letters/{messageId}/replay")
    @PreAuthorize("hasAnyRole('OPERATOR','PLATFORM_ADMIN')")
    public MessageStatusResponse replay(@PathVariable String messageId) {
        return deadLetters.replay(messageId);
    }

    @PostMapping("/dead-letters/{messageId}/discard")
    @PreAuthorize("hasAnyRole('OPERATOR','PLATFORM_ADMIN')")
    public MessageStatusResponse discard(@PathVariable String messageId,
            @Valid @RequestBody(required = false) DiscardRequest body) {
        return deadLetters.discard(messageId, body == null ? null : body.reason());
    }

    @GetMapping("/failed-messages")
    @PreAuthorize("hasAnyRole('OPERATOR','TENANT_ADMIN','PLATFORM_ADMIN')")
    public PageResponse<MessageSummary> failed(
            @RequestParam(required = false) MessageStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var filter = new NotificationService.MessageFilter(status, channel, category, priority, null, null, from, to, null);
        return PageResponse.of(notifications.listByStatuses(INVESTIGATION, filter,
                Pagination.of(page, size, sort, SORTABLE_FAILED, "statusChangedAt", Sort.Direction.DESC)));
    }
}
