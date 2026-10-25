package com.example.messaging.messaging;

import com.example.messaging.domain.Priority;
import java.util.Map;

/** Snapshot of bus state for ops endpoints and gauges. {@code oldestAgeMillis} is null when the bus is empty. */
public record QueueStats(
        Map<Priority, Long> depthByPriority,
        long inFlight,
        int consumers,
        long dlqDepth,
        long delayed,
        Long oldestAgeMillis,
        boolean paused) {

    public long totalDepth() {
        return depthByPriority.values().stream().mapToLong(Long::longValue).sum();
    }
}
