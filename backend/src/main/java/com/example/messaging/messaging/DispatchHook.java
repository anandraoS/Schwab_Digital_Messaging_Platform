package com.example.messaging.messaging;

import com.example.messaging.provider.ProviderResult;

/**
 * Optional extension point around {@link DispatchConsumer}: fault injection in tests (simulated worker death between
 * the provider call and persisting its outcome) and operational tracing. No hook is registered in production; hooks
 * must not log message content.
 */
public interface DispatchHook {

    /** Before any work on a delivery. May throw to simulate an unexpected failure (poison handling). */
    default void beforeProcess(DispatchEnvelope envelope, int deliveryCount) {
    }

    /** After the attempt was claimed and recorded as STARTED, immediately before the provider call. */
    default void beforeProviderCall(DispatchEnvelope envelope) {
    }

    /** After the provider answered, before the outcome is persisted. May throw to simulate a crash. */
    default void afterProviderCall(DispatchEnvelope envelope, ProviderResult result) {
    }
}
