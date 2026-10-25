package com.example.messaging.domain;

public enum DlqReason {
    RETRIES_EXHAUSTED, POISON_MESSAGE, PERMANENT_FAILURE, OPERATOR
}
