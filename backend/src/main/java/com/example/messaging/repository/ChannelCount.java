package com.example.messaging.repository;

import com.example.messaging.domain.Channel;

public interface ChannelCount {
    Channel getChannel();

    long getCount();
}
