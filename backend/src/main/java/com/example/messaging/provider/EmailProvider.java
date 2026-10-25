package com.example.messaging.provider;

import com.example.messaging.domain.Channel;

public interface EmailProvider extends ChannelProvider {
    @Override
    default Channel channel() {
        return Channel.EMAIL;
    }
}
