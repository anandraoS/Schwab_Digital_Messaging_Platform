package com.example.messaging.messaging;

import com.example.messaging.common.Ids;
import com.example.messaging.domain.OutboxEvent;
import com.example.messaging.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes dispatch outbox rows from the pipeline itself (deferral, fallback, reconciliation re-queue). Joins the
 * caller's transaction so the state change and the outbox row commit atomically (ADR-0009).
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository outbox, ObjectMapper mapper, Clock clock) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void enqueue(DispatchEnvelope envelope, Instant availableAt) {
        Instant now = clock.instant();
        OutboxEvent e = new OutboxEvent();
        e.setId(Ids.newId());
        e.setAggregateId(envelope.messageId());
        e.setTopic(OutboxEvent.TOPIC_DISPATCH);
        e.setPayload(serialize(envelope));
        e.setStatus(OutboxEvent.NEW);
        e.setAvailableAt(availableAt == null ? now : availableAt);
        e.setCreatedAt(now);
        outbox.save(e);
    }

    public String serialize(DispatchEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialise dispatch envelope", ex);
        }
    }

    public DispatchEnvelope parse(String payload) {
        try {
            return mapper.readValue(payload, DispatchEnvelope.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unreadable dispatch envelope", ex);
        }
    }
}
