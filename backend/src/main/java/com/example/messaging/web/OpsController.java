package com.example.messaging.web;

import com.example.messaging.service.OpsService;
import com.example.messaging.web.dto.OpsDtos.ConsumerState;
import com.example.messaging.web.dto.OpsDtos.OpsSummary;
import com.example.messaging.web.dto.OpsDtos.QueueStatsView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Queue statistics, dashboard aggregates and consumer control. */
@RestController
@RequestMapping(value = "/api/v1/ops", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Queues")
public class OpsController {

    private final OpsService ops;

    public OpsController(OpsService ops) {
        this.ops = ops;
    }

    @GetMapping("/queues")
    @PreAuthorize("hasAnyRole('OPERATOR','PLATFORM_ADMIN')")
    @Operation(summary = "Queue depth per priority, in-flight, consumers, DLQ depth, retry-pending, oldest age")
    public QueueStatsView queues() {
        return ops.queues();
    }

    @GetMapping("/summary")
    @Operation(summary = "Dashboard aggregates for the last 24 hours (tenant scoped)")
    public OpsSummary summary() {
        return ops.summary();
    }

    @PostMapping("/consumers/pause")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Pause dispatch consumers (publishing and the outbox keep working)")
    public ConsumerState pause() {
        return ops.pause();
    }

    @PostMapping("/consumers/resume")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Resume dispatch consumers")
    public ConsumerState resume() {
        return ops.resume();
    }
}
