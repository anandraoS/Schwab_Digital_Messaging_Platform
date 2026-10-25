package com.example.messaging.provider;

import com.example.messaging.domain.Channel;

public interface SmsProvider extends ChannelProvider {
    @Override
    default Channel channel() {
        return Channel.SMS;
    }
}
