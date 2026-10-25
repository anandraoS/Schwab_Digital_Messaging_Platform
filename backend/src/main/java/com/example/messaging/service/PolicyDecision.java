package com.example.messaging.service;

import java.time.Instant;

/** Outcome of the delivery policy: send now, send later (quiet hours) or never. */
public record PolicyDecision(Type type, Instant until, PolicyReason reason) {

    public enum Type { ALLOW, DEFER, REJECT }

    public static PolicyDecision allow() {
        return new PolicyDecision(Type.ALLOW, null, null);
    }

    public static PolicyDecision defer(Instant until) {
        return new PolicyDecision(Type.DEFER, until, null);
    }

    public static PolicyDecision reject(PolicyReason reason) {
        return new PolicyDecision(Type.REJECT, null, reason);
    }

    public boolean isAllowed() {
        return type == Type.ALLOW;
    }

    public boolean isDeferred() {
        return type == Type.DEFER;
    }

    public boolean isRejected() {
        return type == Type.REJECT;
    }
}
