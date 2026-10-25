package com.example.messaging.repository;

import com.example.messaging.domain.MessageStatus;

public interface StatusCount {
    MessageStatus getStatus();

    long getCount();
}
