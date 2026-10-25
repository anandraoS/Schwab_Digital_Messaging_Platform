package com.example.messaging.domain;

public enum Category {
    TRANSACTIONAL, SECURITY_ALERT, SERVICE, MARKETING;

    public boolean isMarketing() {
        return this == MARKETING;
    }
}
