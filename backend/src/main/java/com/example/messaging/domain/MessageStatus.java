package com.example.messaging.domain;

import java.util.Set;

public enum MessageStatus {
    ACCEPTED, VALIDATED, QUEUED, PROCESSING, SENT_TO_PROVIDER, DELIVERED,
    REJECTED, RETRY_PENDING, FAILED, DEAD_LETTERED, CANCELLED, DELIVERY_UNKNOWN;

    private static final Set<MessageStatus> HARD_TERMINAL = Set.of(DELIVERED, REJECTED, CANCELLED);
    private static final Set<MessageStatus> OPERATOR_TERMINAL = Set.of(FAILED, DEAD_LETTERED);

    /** No transition at all is possible. */
    public boolean isHardTerminal() {
        return HARD_TERMINAL.contains(this);
    }

    /** Terminal unless an operator requeues / replays (FAILED, DEAD_LETTERED). */
    public boolean isOperatorTerminal() {
        return OPERATOR_TERMINAL.contains(this);
    }

    /** What the API reports as {@code terminal}: nothing will happen automatically any more. */
    public boolean isTerminal() {
        return isHardTerminal() || isOperatorTerminal();
    }
}
