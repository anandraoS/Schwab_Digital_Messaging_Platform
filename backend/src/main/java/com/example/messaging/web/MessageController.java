package com.example.messaging.web;

import com.example.messaging.common.PageResponse;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.Priority;
import com.example.messaging.messaging.BackpressureGuard;
import com.example.messaging.service.NotificationService;
import com.example.messaging.web.dto.MessageDtos.MessageAcceptedResponse;
import com.example.messaging.web.dto.MessageDtos.MessageDetail;
import com.example.messaging.web.dto.MessageDtos.MessageStatusResponse;
import com.example.messaging.web.dto.MessageDtos.MessageSummary;
import com.example.messaging.web.dto.MessageDtos.SubmitMessageRequest;
import com.example.messaging.web.dto.MessageDtos.TimelineEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/messages", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Messages")
public class MessageController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "status", "priority", "channel",
            "scheduledAt", "category");

    private final NotificationService notifications;
    private final BackpressureGuard backpressure;

    public MessageController(NotificationService notifications, BackpressureGuard backpressure) {
        this.notifications = notifications;
        this.backpressure = backpressure;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('SENDER','TENANT_ADMIN','PLATFORM_ADMIN')")
    @Operation(summary = "Submit a message for asynchronous delivery (idempotent)")
    public ResponseEntity<MessageAcceptedResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitMessageRequest request) {
        backpressure.check(); // 503 + Retry-After when the dispatch backlog is full
        NotificationService.SubmitResult result = notifications.submit(request, idempotencyKey);
        MessageAcceptedResponse body = result.response();
        if (result.replay()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true")
                    .location(URI.create("/api/v1/messages/" + body.id())).body(body);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/messages/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Message detail with attempts and delivery events (no content, masked address)")
    public MessageDetail get(@PathVariable String id) {
        return notifications.get(id);
    }

    @GetMapping
    @Operation(summary = "Search messages (filters, pagination, sorting)")
    public PageResponse<MessageSummary> list(
            @RequestParam(required = false) MessageStatus status,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) String recipientId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        var pageable = Pagination.of(page, size, sort, SORTABLE, "createdAt", Sort.Direction.DESC);
        var filter = new NotificationService.MessageFilter(status, channel, category, priority, recipientId,
                correlationId, from, to, q);
        return PageResponse.of(notifications.list(filter, pageable));
    }

    @GetMapping("/{id}/status")
    public MessageStatusResponse status(@PathVariable String id) {
        return notifications.status(id);
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Lifecycle timeline: audit events, attempts and delivery events merged by time")
    public List<TimelineEntry> history(@PathVariable String id) {
        return notifications.history(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SENDER','TENANT_ADMIN','OPERATOR','PLATFORM_ADMIN')")
    @Operation(summary = "Cancel a message that was not yet handed to a provider (409 otherwise)")
    public MessageStatusResponse cancel(@PathVariable String id) {
        return notifications.cancel(id);
    }

    @PostMapping("/{id}/requeue")
    @PreAuthorize("hasAnyRole('OPERATOR','PLATFORM_ADMIN')")
    @Operation(summary = "Operator requeue of a FAILED or DEAD_LETTERED message")
    public MessageStatusResponse requeue(@PathVariable String id) {
        return notifications.requeue(id);
    }
}
