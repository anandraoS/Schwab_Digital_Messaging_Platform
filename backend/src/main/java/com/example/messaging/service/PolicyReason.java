package com.example.messaging.service;

/** Why a message was rejected by policy (stored as {@code status_reason}). */
public enum PolicyReason {
    RECIPIENT_DELETED,
    NO_ADDRESS,
    SUPPRESSED,
    OPTED_OUT,
    CHANNEL_DISABLED,
    INVALID_PREFERENCES
}
