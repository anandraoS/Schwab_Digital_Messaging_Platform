package com.example.messaging.domain;

/** Audit action names (string constants, stored as VARCHAR). */
public final class AuditAction {
    public static final String MESSAGE_ACCEPTED = "MESSAGE_ACCEPTED";
    public static final String MESSAGE_REJECTED = "MESSAGE_REJECTED";
    public static final String MESSAGE_TRANSITION = "MESSAGE_TRANSITION";
    public static final String MESSAGE_CANCELLED = "MESSAGE_CANCELLED";
    public static final String MESSAGE_REQUEUED = "MESSAGE_REQUEUED";
    public static final String MESSAGE_DISCARDED = "MESSAGE_DISCARDED";
    public static final String MESSAGE_REPLAYED = "MESSAGE_REPLAYED";
    public static final String DELIVERY_EVENT_CONFLICT = "DELIVERY_EVENT_CONFLICT";
    public static final String TEMPLATE_CREATED = "TEMPLATE_CREATED";
    public static final String TEMPLATE_VERSION_CREATED = "TEMPLATE_VERSION_CREATED";
    public static final String TEMPLATE_VERSION_EDITED = "TEMPLATE_VERSION_EDITED";
    public static final String TEMPLATE_SUBMITTED = "TEMPLATE_SUBMITTED";
    public static final String TEMPLATE_APPROVED = "TEMPLATE_APPROVED";
    public static final String TEMPLATE_REJECTED = "TEMPLATE_REJECTED";
    public static final String TEMPLATE_RETIRED = "TEMPLATE_RETIRED";
    public static final String PREFERENCE_UPDATED = "PREFERENCE_UPDATED";
    public static final String SUPPRESSION_ADDED = "SUPPRESSION_ADDED";
    public static final String SUPPRESSION_REMOVED = "SUPPRESSION_REMOVED";
    public static final String PROVIDER_CONFIG_UPDATED = "PROVIDER_CONFIG_UPDATED";
    public static final String CONSUMERS_PAUSED = "CONSUMERS_PAUSED";
    public static final String CONSUMERS_RESUMED = "CONSUMERS_RESUMED";
    public static final String STUCK_SWEEP = "STUCK_SWEEP";
    public static final String RECONCILIATION_CHECK = "RECONCILIATION_CHECK";
    public static final String MESSAGE_FALLBACK = "MESSAGE_FALLBACK";
    public static final String REPLAY_PRECHECK = "REPLAY_PRECHECK";
    public static final String DELIVERY_EVENT_APPLIED = "DELIVERY_EVENT_APPLIED";
    public static final String BULK_JOB_CREATED = "BULK_JOB_CREATED";
    public static final String BULK_JOB_STOPPED = "BULK_JOB_STOPPED";
    public static final String BULK_JOB_RESTARTED = "BULK_JOB_RESTARTED";

    private AuditAction() {}
}
