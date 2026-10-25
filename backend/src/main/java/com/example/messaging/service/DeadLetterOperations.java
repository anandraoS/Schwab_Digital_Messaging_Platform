package com.example.messaging.service;

import com.example.messaging.web.dto.MessageDtos.MessageStatusResponse;

/**
 * Operator actions on dead letters. Stage 1 ships {@link DefaultDeadLetterOperations} (pure state transition via
 * {@link NotificationService}). Stage 2's {@code ReplayService} may implement this interface as a {@code @Primary}
 * bean to add bus-level replay behaviour.
 */
public interface DeadLetterOperations {

    /** DEAD_LETTERED to QUEUED (re-published through the outbox). */
    MessageStatusResponse replay(String messageId);

    /** Marks the dead letter as discarded; status stays DEAD_LETTERED. */
    MessageStatusResponse discard(String messageId, String reason);
}
