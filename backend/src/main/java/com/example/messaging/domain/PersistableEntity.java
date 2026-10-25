package com.example.messaging.domain;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Base for entities with application-assigned String ids and no {@code @Version}: lets Spring Data call
 * {@code persist} (an INSERT) instead of {@code merge} (SELECT + INSERT).
 */
@MappedSuperclass
public abstract class PersistableEntity implements Persistable<String> {

    @Transient
    private boolean fresh = true;

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.fresh = false;
    }
}
