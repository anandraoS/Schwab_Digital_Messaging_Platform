package com.example.messaging.domain;

/** Thrown when a lifecycle transition is not allowed by {@link MessageStateMachine}; mapped to HTTP 409. */
public class IllegalStateTransitionException extends RuntimeException {

    private final transient MessageStatus from;
    private final transient MessageStatus to;

    public IllegalStateTransitionException(MessageStatus from, MessageStatus to) {
        super("Illegal message state transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public IllegalStateTransitionException(MessageStatus from, String action) {
        super("Action '" + action + "' is not permitted while message is " + from);
        this.from = from;
        this.to = null;
    }

    public MessageStatus getFrom() {
        return from;
    }

    public MessageStatus getTo() {
        return to;
    }
}
