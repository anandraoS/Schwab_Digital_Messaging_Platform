package com.example.messaging.domain;

/** Weight is the RabbitMQ message priority (x-max-priority = 9). */
public enum Priority {
    CRITICAL(9), HIGH(6), NORMAL(3), LOW(1);

    private final int weight;

    Priority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public boolean atLeast(Priority other) {
        return weight >= other.weight;
    }
}
