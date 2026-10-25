package com.example.messaging.domain;

import static com.example.messaging.domain.MessageStatus.*;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Pure, table-driven message lifecycle (CONTRACT 3.1). */
public final class MessageStateMachine {

    private static final Map<MessageStatus, Set<MessageStatus>> TABLE = new EnumMap<>(MessageStatus.class);

    static {
        TABLE.put(ACCEPTED, EnumSet.of(VALIDATED, REJECTED));
        TABLE.put(VALIDATED, EnumSet.of(QUEUED, CANCELLED, REJECTED));
        TABLE.put(QUEUED, EnumSet.of(PROCESSING, CANCELLED));
        TABLE.put(PROCESSING, EnumSet.of(SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED));
        TABLE.put(RETRY_PENDING, EnumSet.of(QUEUED, DEAD_LETTERED, CANCELLED));
        TABLE.put(SENT_TO_PROVIDER, EnumSet.of(DELIVERED, FAILED, DELIVERY_UNKNOWN));
        TABLE.put(DELIVERY_UNKNOWN, EnumSet.of(DELIVERED, FAILED));
        TABLE.put(FAILED, EnumSet.of(DEAD_LETTERED, QUEUED));
        TABLE.put(DEAD_LETTERED, EnumSet.of(QUEUED));
        TABLE.put(DELIVERED, EnumSet.noneOf(MessageStatus.class));
        TABLE.put(REJECTED, EnumSet.noneOf(MessageStatus.class));
        TABLE.put(CANCELLED, EnumSet.noneOf(MessageStatus.class));
    }

    private static final Set<MessageStatus> CANCELLABLE = EnumSet.of(VALIDATED, QUEUED, RETRY_PENDING);
    private static final Set<MessageStatus> REQUEUEABLE = EnumSet.of(FAILED, DEAD_LETTERED);

    private MessageStateMachine() {}

    public static Set<MessageStatus> allowedFrom(MessageStatus from) {
        return Set.copyOf(TABLE.get(from));
    }

    public static boolean canTransition(MessageStatus from, MessageStatus to) {
        return from != null && to != null && TABLE.get(from).contains(to);
    }

    public static void assertTransition(MessageStatus from, MessageStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }

    /** Cancellation is permitted only from VALIDATED, QUEUED, RETRY_PENDING. */
    public static boolean canCancel(MessageStatus from) {
        return CANCELLABLE.contains(from);
    }

    /** Operator requeue/replay is permitted only from FAILED and DEAD_LETTERED. */
    public static boolean canRequeue(MessageStatus from) {
        return REQUEUEABLE.contains(from);
    }

    /** Transitions that only an operator may trigger (never the automatic pipeline). */
    public static boolean isOperatorOnly(MessageStatus from, MessageStatus to) {
        return to == QUEUED && REQUEUEABLE.contains(from);
    }

    /**
     * Receipt-driven transitions (CONTRACT 3.1, out-of-order callbacks): a terminal receipt that arrives while the
     * message is still PROCESSING (callback overtook the persist of SENT_TO_PROVIDER) is applied.
     */
    public static boolean canTransitionViaReceipt(MessageStatus from, MessageStatus to) {
        return canTransition(from, to) || (from == PROCESSING && (to == DELIVERED || to == FAILED));
    }

    public enum ReceiptResolution { APPLY_DELIVERED, APPLY_FAILED, IGNORE_LOWER_RANK, CONFLICT, IGNORE_STATE }

    /** Decides what a provider receipt does to the current status (monotonic rank rule, CONTRACT 3.4). */
    public static ReceiptResolution resolveReceipt(MessageStatus current, DeliveryEventType type) {
        if (type.rank() < 3) {
            return ReceiptResolution.IGNORE_LOWER_RANK;
        }
        if (current == DELIVERED) {
            return type == DeliveryEventType.DELIVERED
                    ? ReceiptResolution.IGNORE_LOWER_RANK : ReceiptResolution.CONFLICT;
        }
        boolean receivable = current == PROCESSING || current == SENT_TO_PROVIDER || current == DELIVERY_UNKNOWN;
        if (!receivable) {
            // a terminal-negative state followed by another terminal receipt: keep the first outcome
            return (current == FAILED || current == DEAD_LETTERED) ? ReceiptResolution.CONFLICT
                    : ReceiptResolution.IGNORE_STATE;
        }
        return type == DeliveryEventType.DELIVERED ? ReceiptResolution.APPLY_DELIVERED
                : ReceiptResolution.APPLY_FAILED;
    }
}
