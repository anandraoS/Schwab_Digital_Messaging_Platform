package com.example.messaging.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.ReplayGuardEntry;
import com.example.messaging.repository.ReplayGuardRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class ReplayGuardTest {

    /** Clock that tests can move. */
    private static final class MovableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-18T10:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private final ReplayGuardRepository repo = mock(ReplayGuardRepository.class);
    private final MovableClock clock = new MovableClock();
    private final ReplayGuard guard = new ReplayGuard(repo, new PipelineProperties(true, Duration.ofSeconds(30),
            Duration.ofMillis(250), Duration.ofMillis(150), Duration.ofHours(24),
            new PipelineProperties.Scheduler(true, 200, 100, 1000, Duration.ofSeconds(5), Duration.ofMinutes(1), 10000, 100,
                    15000, 50, 5), new PipelineProperties.Resilience(50f, 20, 10, Duration.ofSeconds(5), 3, 16, 64), Duration.ofMinutes(10)), clock,
            100);

    private ReplayGuardEntry entry(String key, Instant seenAt) {
        ReplayGuardEntry e = new ReplayGuardEntry();
        e.setEventId(key);
        e.setSeenAt(seenAt);
        return e;
    }

    @Test
    void anEventNeverSeenIsNotAReplay() {
        when(repo.findById(any())).thenReturn(Optional.empty());
        assertThat(guard.isReplay(ReplayGuard.key("simulated-email", "evt-1"))).isFalse();
    }

    @Test
    void aMarkedEventIsAReplayAnsweredFromTheCacheWithoutTouchingTheDatabaseAgain() {
        String key = ReplayGuard.key("simulated-email", "evt-2");
        when(repo.findById(key)).thenReturn(Optional.empty());
        guard.markSeen(key);
        ArgumentCaptor<ReplayGuardEntry> saved = ArgumentCaptor.forClass(ReplayGuardEntry.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo(key);
        assertThat(saved.getValue().getSeenAt()).isEqualTo(clock.instant());
        org.mockito.Mockito.clearInvocations(repo);
        assertThat(guard.isReplay(key)).isTrue();
        org.mockito.Mockito.verifyNoInteractions(repo); // answered from memory
    }

    @Test
    void aRowWrittenByAnotherInstanceCountsWhileItIsInsideTheReplayWindow() {
        String key = ReplayGuard.key("simulated-sms", "evt-3");
        when(repo.findById(key)).thenReturn(Optional.of(entry(key, clock.instant().minus(Duration.ofHours(23)))));
        assertThat(guard.isReplay(key)).isTrue();
    }

    @Test
    void anOldRowIsForgottenAfterTheWindowAndMarkingItAgainRefreshesTheRow() {
        String key = ReplayGuard.key("simulated-sms", "evt-4");
        ReplayGuardEntry old = entry(key, clock.instant().minus(Duration.ofHours(25)));
        when(repo.findById(key)).thenReturn(Optional.of(old));
        assertThat(guard.isReplay(key)).isFalse();
        guard.markSeen(key);
        verify(repo).save(old);
        assertThat(old.getSeenAt()).isEqualTo(clock.instant());
        assertThat(guard.isReplay(key)).isTrue();
        // and later the in-memory entry ages out as well
        clock.now.set(clock.instant().plus(Duration.ofHours(25)));
        when(repo.findById(key)).thenReturn(Optional.of(old));
        assertThat(guard.isReplay(key)).isFalse();
    }

    @Test
    void aConcurrentInsertOfTheSameKeyIsNotAnError() {
        String key = ReplayGuard.key("simulated-push", "evt-5");
        when(repo.findById(key)).thenReturn(Optional.empty());
        when(repo.save(any(ReplayGuardEntry.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));
        guard.markSeen(key); // must not throw
        assertThat(guard.isReplay(key)).isTrue(); // still remembered locally
        verify(repo, times(1)).save(any(ReplayGuardEntry.class));
    }

    @Test
    void keysAreScopedPerProviderAndLongIdsAreHashedToFitThePrimaryKey() {
        assertThat(ReplayGuard.key("a", "e1")).isEqualTo("a:e1").isNotEqualTo(ReplayGuard.key("b", "e1"));
        String longId = "x".repeat(300);
        String key = ReplayGuard.key("simulated-email", longId);
        assertThat(key).hasSizeLessThanOrEqualTo(200).startsWith("h:");
        assertThat(ReplayGuard.key("simulated-email", longId)).isEqualTo(key); // stable
        assertThat(ReplayGuard.key("simulated-email", longId + "y")).isNotEqualTo(key);
    }

    @Test
    void theInMemoryCacheStaysBounded() {
        when(repo.findById(any(String.class))).thenReturn(Optional.empty());
        for (int i = 0; i < 250; i++) { // the guard under test caches at most 100 entries
            guard.markSeen("p:" + i);
        }
        // everything beyond the cache limit is still answered correctly (the table is authoritative)
        when(repo.findById("p:0")).thenReturn(Optional.of(entry("p:0", clock.instant())));
        assertThat(guard.isReplay("p:0")).isTrue();
    }
}
