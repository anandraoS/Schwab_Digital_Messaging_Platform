package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.example.messaging.messaging.BusRejectedException;
import com.example.messaging.messaging.DispatchEnvelope;
import com.example.messaging.service.NotificationService;
import com.example.messaging.web.dto.MessageDtos.SubmitMessageRequest;
import com.example.messaging.web.dto.MessageDtos.TemplateRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Bounded queues: the bus refuses at the limit, the outbox keeps what was refused, and gauges tell the story. */
class BackpressureIT extends AbstractPipelineIT {

    @Autowired
    NotificationService notifications;

    private DispatchEnvelope ghost(Priority p) {
        return DispatchEnvelope.of("ghost-" + System.nanoTime(), RETAIL, Channel.EMAIL, p, 1, "c", Instant.now());
    }

    @Test
    void theBusRefusesPublishesAtTheLimitWithARetryAfterHintAndReportsItsDepth() throws Exception {
        quiesce();
        limits.setMaxQueueDepth(2);
        bus.publish(ghost(Priority.LOW));
        bus.publish(ghost(Priority.CRITICAL));
        assertThatThrownBy(() -> bus.publish(ghost(Priority.NORMAL))).isInstanceOf(BusRejectedException.class)
                .satisfies(e -> assertThat(((BusRejectedException) e).getRetryAfterSeconds()).isGreaterThanOrEqualTo(1));
        var stats = bus.stats();
        assertThat(stats.totalDepth()).isEqualTo(2);
        assertThat(stats.depthByPriority().get(Priority.CRITICAL)).isEqualTo(1);
        assertThat(stats.depthByPriority().get(Priority.LOW)).isEqualTo(1);
        assertThat(stats.paused()).isTrue();
        Thread.sleep(30);
        assertThat(bus.stats().oldestAgeMillis()).isGreaterThanOrEqualTo(30);
        // gauges follow the live statistics
        assertThat(meters.get("messaging.queue.depth").tag("priority", "CRITICAL").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("messaging.queue.oldest.age").gauge().value()).isGreaterThan(0.0);
        assertThat(meters.get("messaging.queue.dlq.depth").gauge().value()).isGreaterThanOrEqualTo(0.0);
        limits.setMaxQueueDepth(5000);
        bus.resume();
        awaitIdle();
    }

    @Test
    void rowsTheBusRefusedStayInTheOutboxAndAreNotLost() throws Exception {
        quiesce();
        limits.setMaxQueueDepth(1);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(notifications.submitInternal(RETAIL, new SubmitMessageRequest("bp-" + i, newRecipient(RETAIL), null,
                    Channel.EMAIL, com.example.messaging.domain.Category.TRANSACTIONAL, Priority.NORMAL,
                    new TemplateRef("TRADE_CONFIRMATION", "en-US", null),
                    Map.of("accountLast4", "1234", "quantity", "1", "symbol", "ACME", "price", "1.00"), null, null, null,
                    null), newKey(), null, "test").response().id());
        }
        // the background publisher takes what the bus accepts; the rest stays NEW
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> bus.stats().totalDepth() >= 1);
        Thread.sleep(300);
        assertThat(bus.stats().totalDepth()).isEqualTo(1);
        int stillNew = 0;
        for (String id : ids) {
            stillNew += jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and status = 'NEW'",
                    Integer.class, id);
        }
        assertThat(stillNew).isEqualTo(3);

        limits.setMaxQueueDepth(5000);
        bus.resume();
        for (String id : ids) {
            awaitStatus(id, "SENT_TO_PROVIDER");
            assertThat(attemptRows(id)).isEqualTo(1);
        }
        assertThat(email.callCount()).isEqualTo(4);
    }

    @Test
    void opsQueuesReportsTheQueueAndTheConsumerStateAndPauseResumeAreAudited() throws Exception {
        String admin = platformAdmin();
        mvc.perform(get("/api/v1/ops/queues").header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.consumers").value(4))
                .andExpect(jsonPath("$.depthByPriority.CRITICAL").value(0))
                .andExpect(jsonPath("$.depthByPriority.LOW").value(0));
        mvc.perform(post("/api/v1/ops/consumers/pause").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true));
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        Thread.sleep(300);
        assertThat(statusOf(id)).isEqualTo("QUEUED"); // paused consumers leave it alone
        mvc.perform(get("/api/v1/ops/queues").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.consumersPaused").value(true))
                .andExpect(jsonPath("$.depthByPriority.NORMAL").value(1))
                .andExpect(jsonPath("$.oldestMessageAgeMs").isNumber());
        mvc.perform(post("/api/v1/ops/consumers/resume").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(false));
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'CONSUMERS_PAUSED'",
                Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'CONSUMERS_RESUMED'",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }
}
