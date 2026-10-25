package com.example.messaging.repository;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.MessageStatus;
import java.time.Instant;

/** Interface projection: the lightweight status read never loads payload columns. */
public interface MessageStatusView {
    String getId();

    String getTenantId();

    MessageStatus getStatus();

    String getStatusReason();

    Channel getChannelUsed();

    int getAttemptCount();

    Instant getUpdatedAt();
}
