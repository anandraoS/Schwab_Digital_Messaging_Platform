package com.example.messaging.domain;

import static com.example.messaging.domain.MessageStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MessageStateMachineTest {

    /** Independent transcription of CONTRACT 3.1 (the production table is not reused here). */
    private static final Map<MessageStatus, Set<MessageStatus>> EXPECTED = new EnumMap<>(MessageStatus.class);

    static {
        EXPECTED.put(ACCEPTED, EnumSet.of(VALIDATED, REJECTED));
        EXPECTED.put(VALIDATED, EnumSet.of(QUEUED, CANCELLED, REJECTED));
        EXPECTED.put(QUEUED, EnumSet.of(PROCESSING, CANCELLED));
        EXPECTED.put(PROCESSING, EnumSet.of(SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED));
        EXPECTED.put(RETRY_PENDING, EnumSet.of(QUEUED, DEAD_LETTERED, CANCELLED));
        EXPECTED.put(SENT_TO_PROVIDER, EnumSet.of(DELIVERED, FAILED, DELIVERY_UNKNOWN));
        EXPECTED.put(DELIVERY_UNKNOWN, EnumSet.of(DELIVERED, FAILED));
        EXPECTED.put(FAILED, EnumSet.of(DEAD_LETTERED, QUEUED));
        EXPECTED.put(DEAD_LETTERED, EnumSet.of(QUEUED));
        EXPECTED.put(DELIVERED, EnumSet.noneOf(MessageStatus.class));
        EXPECTED.put(REJECTED, EnumSet.noneOf(MessageStatus.class));
        EXPECTED.put(CANCELLED, EnumSet.noneOf(MessageStatus.class));
    }

    @Test
    void everyOrderedPairMatchesTheContractTable() {
        int allowed = 0;
        int illegal = 0;
        for (MessageStatus from : MessageStatus.values()) {
            for (MessageStatus to : MessageStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(MessageStateMachine.canTransition(from, to))
                        .as("%s -> %s", from, to).isEqualTo(expected);
                if (expected) {
                    allowed++;
                    MessageStateMachine.assertTransition(from, to);
                } else {
                    illegal++;
                    assertThatThrownBy(() -> MessageStateMachine.assertTransition(from, to))
                            .isInstanceOf(IllegalStateTransitionException.class)
                            .hasMessageContaining(from.name()).hasMessageContaining(to.name());
                }
            }
        }
        assertThat(allowed).isEqualTo(23);
        assertThat(allowed + illegal).isEqualTo(144);
    }

    @Test
    void selfTransitionsAreNeverAllowed() {
        for (MessageStatus s : MessageStatus.values()) {
            assertThat(MessageStateMachine.canTransition(s, s)).as(s.name()).isFalse();
        }
    }

    @Test
    void nullsAreNotTransitions() {
        assertThat(MessageStateMachine.canTransition(null, QUEUED)).isFalse();
        assertThat(MessageStateMachine.canTransition(QUEUED, null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(MessageStatus.class)
    void cancelIsOnlyPermittedFromValidatedQueuedRetryPending(MessageStatus s) {
        boolean expected = s == VALIDATED || s == QUEUED || s == RETRY_PENDING;
        assertThat(MessageStateMachine.canCancel(s)).isEqualTo(expected);
        // every cancellable state must actually be able to move to CANCELLED
        if (expected) {
            assertThat(MessageStateMachine.canTransition(s, CANCELLED)).isTrue();
        }
    }

    @Test
    void hardTerminalStatesHaveNoExitAndOperatorTerminalStatesOnlyOperatorExits() {
        for (MessageStatus s : EnumSet.of(DELIVERED, REJECTED, CANCELLED)) {
            assertThat(s.isHardTerminal()).isTrue();
            assertThat(MessageStateMachine.allowedFrom(s)).isEmpty();
        }
        assertThat(MessageStatus.FAILED.isOperatorTerminal()).isTrue();
        assertThat(MessageStatus.DEAD_LETTERED.isOperatorTerminal()).isTrue();
        assertThat(MessageStateMachine.isOperatorOnly(FAILED, QUEUED)).isTrue();
        assertThat(MessageStateMachine.isOperatorOnly(DEAD_LETTERED, QUEUED)).isTrue();
        assertThat(MessageStateMachine.isOperatorOnly(RETRY_PENDING, QUEUED)).isFalse();
        assertThat(MessageStateMachine.isOperatorOnly(VALIDATED, QUEUED)).isFalse();
        assertThat(MessageStateMachine.canRequeue(FAILED)).isTrue();
        assertThat(MessageStateMachine.canRequeue(DEAD_LETTERED)).isTrue();
        assertThat(MessageStateMachine.canRequeue(DELIVERED)).isFalse();
        assertThat(MessageStateMachine.canRequeue(CANCELLED)).isFalse();
    }

    @Test
    void providerAcceptanceNeverImpliesDelivery() {
        assertThat(MessageStateMachine.canTransition(PROCESSING, DELIVERED)).isFalse();
        assertThat(MessageStateMachine.canTransition(SENT_TO_PROVIDER, DELIVERED)).isTrue();
        // only a provider receipt may skip ahead from PROCESSING
        assertThat(MessageStateMachine.canTransitionViaReceipt(PROCESSING, DELIVERED)).isTrue();
        assertThat(MessageStateMachine.canTransitionViaReceipt(PROCESSING, FAILED)).isTrue();
        assertThat(MessageStateMachine.canTransitionViaReceipt(QUEUED, DELIVERED)).isFalse();
        assertThat(MessageStateMachine.canTransitionViaReceipt(CANCELLED, DELIVERED)).isFalse();
    }

    @Test
    void apiTerminalFlagCoversFinalAndOperatorFinalStates() {
        assertThat(DELIVERED.isTerminal() && REJECTED.isTerminal() && CANCELLED.isTerminal()
                && FAILED.isTerminal() && DEAD_LETTERED.isTerminal()).isTrue();
        assertThat(QUEUED.isTerminal() || PROCESSING.isTerminal() || RETRY_PENDING.isTerminal()
                || SENT_TO_PROVIDER.isTerminal() || DELIVERY_UNKNOWN.isTerminal()).isFalse();
    }

    @Test
    void receiptResolutionFollowsTheMonotonicRankRule() {
        var r = MessageStateMachine.ReceiptResolution.class;
        // DELIVERED applies from PROCESSING / SENT_TO_PROVIDER / DELIVERY_UNKNOWN
        for (MessageStatus s : EnumSet.of(PROCESSING, SENT_TO_PROVIDER, DELIVERY_UNKNOWN)) {
            assertThat(MessageStateMachine.resolveReceipt(s, DeliveryEventType.DELIVERED))
                    .isEqualTo(MessageStateMachine.ReceiptResolution.APPLY_DELIVERED);
            assertThat(MessageStateMachine.resolveReceipt(s, DeliveryEventType.BOUNCED))
                    .isEqualTo(MessageStateMachine.ReceiptResolution.APPLY_FAILED);
        }
        // lower rank never overwrites a higher rank
        assertThat(MessageStateMachine.resolveReceipt(DELIVERED, DeliveryEventType.SENT))
                .isEqualTo(MessageStateMachine.ReceiptResolution.IGNORE_LOWER_RANK);
        assertThat(MessageStateMachine.resolveReceipt(SENT_TO_PROVIDER, DeliveryEventType.PROVIDER_ACCEPTED))
                .isEqualTo(MessageStateMachine.ReceiptResolution.IGNORE_LOWER_RANK);
        // DELIVERED then BOUNCED keeps the first terminal and flags a conflict
        assertThat(MessageStateMachine.resolveReceipt(DELIVERED, DeliveryEventType.BOUNCED))
                .isEqualTo(MessageStateMachine.ReceiptResolution.CONFLICT);
        assertThat(MessageStateMachine.resolveReceipt(FAILED, DeliveryEventType.DELIVERED))
                .isEqualTo(MessageStateMachine.ReceiptResolution.CONFLICT);
        assertThat(MessageStateMachine.resolveReceipt(CANCELLED, DeliveryEventType.DELIVERED))
                .isEqualTo(MessageStateMachine.ReceiptResolution.IGNORE_STATE);
        assertThat(r.getEnumConstants()).hasSize(5);
    }
}
