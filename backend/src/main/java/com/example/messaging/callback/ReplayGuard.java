package com.example.messaging.callback;

import com.example.messaging.common.Ids;
import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.ReplayGuardEntry;
import com.example.messaging.repository.ReplayGuardRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Remembers which {@code X-Event-Id}s were already processed so a replayed webhook causes no state change. A small
 * in-memory cache answers the common case; the {@code replay_guard} table is the source of truth across instances
 * and restarts. An entry only counts while it is younger than {@code messaging.pipeline.replay-window}. Rows are
 * purged by the retention job.
 */
@Component
public class ReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(ReplayGuard.class);
    private static final int DEFAULT_CACHE_LIMIT = 50_000;

    private final ReplayGuardRepository repository;
    private final PipelineProperties pipeline;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> cache = new ConcurrentHashMap<>();
    private final int cacheLimit;

    @org.springframework.beans.factory.annotation.Autowired
    public ReplayGuard(ReplayGuardRepository repository, PipelineProperties pipeline, Clock clock) {
        this(repository, pipeline, clock, DEFAULT_CACHE_LIMIT);
    }

    ReplayGuard(ReplayGuardRepository repository, PipelineProperties pipeline, Clock clock, int cacheLimit) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.clock = clock;
        this.cacheLimit = cacheLimit;
    }

    /** Key = provider + event id, hashed when it would not fit the 200 character primary key. */
    public static String key(String providerId, String eventId) {
        String k = providerId + ":" + eventId;
        return k.length() <= 190 ? k : "h:" + Ids.sha256Hex(k);
    }

    /** True when this event id was already processed within the replay window. */
    public boolean isReplay(String key) {
        Instant cutoff = clock.instant().minus(pipeline.replayWindow());
        Instant cached = cache.get(key);
        if (cached != null && cached.isAfter(cutoff)) {
            return true;
        }
        Optional<ReplayGuardEntry> row = repository.findById(key);
        if (row.isPresent() && row.get().getSeenAt().isAfter(cutoff)) {
            remember(key, row.get().getSeenAt());
            return true;
        }
        return false;
    }

    /** Records the event id after it was processed successfully. Idempotent and safe under concurrency. */
    public void markSeen(String key) {
        Instant now = clock.instant();
        remember(key, now);
        try {
            Optional<ReplayGuardEntry> existing = repository.findById(key);
            if (existing.isPresent()) {
                ReplayGuardEntry e = existing.get();
                e.setSeenAt(now);
                repository.save(e);
            } else {
                ReplayGuardEntry e = new ReplayGuardEntry();
                e.setEventId(key);
                e.setSeenAt(now);
                repository.save(e);
            }
        } catch (DataIntegrityViolationException e) {
            log.debug("Replay guard entry already stored by a concurrent request");
        }
    }

    private void remember(String key, Instant seenAt) {
        if (cache.size() >= cacheLimit) {
            Instant cutoff = clock.instant().minus(pipeline.replayWindow());
            cache.values().removeIf(t -> t.isBefore(cutoff));
            if (cache.size() >= cacheLimit) {
                cache.clear(); // the table stays authoritative
            }
        }
        cache.put(key, seenAt);
    }
}
