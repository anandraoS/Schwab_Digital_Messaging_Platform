package com.example.messaging.service;

import com.example.messaging.web.dto.MessageDtos.MessageStatusResponse;
import org.springframework.stereotype.Service;

@Service
public class DefaultDeadLetterOperations implements DeadLetterOperations {

    private final NotificationService notifications;

    public DefaultDeadLetterOperations(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public MessageStatusResponse replay(String messageId) {
        return notifications.replay(messageId);
    }

    @Override
    public MessageStatusResponse discard(String messageId, String reason) {
        return notifications.discard(messageId, reason);
    }
}
