package com.example.messaging.domain;

public enum DeliveryEventType {
    PROVIDER_ACCEPTED(1), SENT(1), DEFERRED(1), DELIVERED(3), BOUNCED(3), FAILED(3), REJECTED(3),
    OPENED(2), CLICKED(2), UNSUBSCRIBED(2);

    private final int rank;

    DeliveryEventType(int rank) {
        this.rank = rank;
    }

    /** Monotonic rank (CONTRACT 3.4): a lower rank never overwrites a higher rank. */
    public int rank() {
        return rank;
    }

    public boolean isTerminalPositive() {
        return this == DELIVERED;
    }

    public boolean isTerminalNegative() {
        return this == BOUNCED || this == FAILED || this == REJECTED;
    }
}
