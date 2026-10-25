package com.example.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.messaging.common.Ids;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.example.messaging.domain.Recipient;
import com.example.messaging.domain.RecipientPreference;
import com.example.messaging.repository.RecipientPreferenceRepository;
import com.example.messaging.repository.SuppressionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyServiceTest {

    private static Recipient recipient(String zone) {
        Recipient r = new Recipient();
        r.setId("rcp-test");
        r.setTenantId("tenant-retail");
        r.setEmail("jane@example.test");
        r.setPhoneE164("+15550100001");
        r.setPushToken("push-token-1");
        r.setLocale("en");
        r.setTimeZone(zone);
        return r;
    }

    private static RecipientPreference prefs(boolean marketing, boolean email, boolean sms, boolean push) {
        RecipientPreference p = new RecipientPreference();
        p.setRecipientId("rcp-test");
        p.setMarketingOptIn(marketing);
        p.setEmailEnabled(email);
        p.setSmsEnabled(sms);
        p.setPushEnabled(push);
        return p;
    }

    private static Instant at(String zone, String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZoneId.of(zone)).toInstant();
    }

    private static PolicyDecision decide(Recipient r, RecipientPreference p, boolean suppressed, Channel ch,
            Category cat, Priority pr, Instant now) {
        return PolicyService.decide(r, p, suppressed, ch, cat, pr, now);
    }

    private static final Instant NOON_NY = at("America/New_York", "2026-06-10T12:00:00");

    // ------------------------------------------------------------ step 1: recipient

    @Test
    void deletedRecipientIsRejectedForEveryCategory() {
        Recipient r = recipient("UTC");
        r.setDeleted(true);
        for (Category c : Category.values()) {
            assertThat(decide(r, prefs(true, true, true, true), false, Channel.EMAIL, c, Priority.HIGH, NOON_NY))
                    .isEqualTo(PolicyDecision.reject(PolicyReason.RECIPIENT_DELETED));
        }
        assertThat(decide(null, null, false, Channel.EMAIL, Category.TRANSACTIONAL, Priority.NORMAL, NOON_NY).reason())
                .isEqualTo(PolicyReason.RECIPIENT_DELETED);
    }

    @Test
    void missingAddressForTheChannelIsRejected() {
        Recipient r = recipient("UTC");
        r.setPhoneE164(null);
        r.setPushToken("  ");
        assertThat(decide(r, null, false, Channel.SMS, Category.TRANSACTIONAL, Priority.NORMAL, NOON_NY).reason())
                .isEqualTo(PolicyReason.NO_ADDRESS);
        assertThat(decide(r, null, false, Channel.PUSH, Category.SECURITY_ALERT, Priority.CRITICAL, NOON_NY).reason())
                .isEqualTo(PolicyReason.NO_ADDRESS);
        assertThat(decide(r, null, false, Channel.EMAIL, Category.TRANSACTIONAL, Priority.NORMAL, NOON_NY).isAllowed()).isTrue();
    }

    // ------------------------------------------------------------ step 2: suppression

    @Test
    void suppressionBlocksAllCategoriesOnThatChannel() {
        for (Category c : Category.values()) {
            PolicyDecision d = decide(recipient("UTC"), prefs(true, true, true, true), true, Channel.EMAIL, c,
                    Priority.CRITICAL, NOON_NY);
            assertThat(d).as(c.name()).isEqualTo(PolicyDecision.reject(PolicyReason.SUPPRESSED));
        }
    }

    // ------------------------------------------------------------ step 3: consent

    @Test
    void marketingRequiresExplicitOptInAndEnabledChannel() {
        Recipient r = recipient("America/New_York");
        assertThat(decide(r, null, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, NOON_NY).reason())
                .as("no preference row means no consent").isEqualTo(PolicyReason.OPTED_OUT);
        assertThat(decide(r, prefs(false, true, true, true), false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, NOON_NY).reason())
                .as("marketing opt-out").isEqualTo(PolicyReason.OPTED_OUT);
        assertThat(decide(r, prefs(true, false, true, true), false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, NOON_NY).reason())
                .as("opted in but channel disabled").isEqualTo(PolicyReason.OPTED_OUT);
        assertThat(decide(r, prefs(true, true, true, true), false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, NOON_NY))
                .isEqualTo(PolicyDecision.allow());
    }

    @Test
    void nonMarketingIgnoresMarketingOptOutAndTransactionalIgnoresChannelFlags() {
        Recipient r = recipient("UTC");
        RecipientPreference optedOut = prefs(false, false, false, false);
        Instant noon = at("UTC", "2026-06-10T12:00:00");
        assertThat(decide(r, optedOut, false, Channel.EMAIL, Category.TRANSACTIONAL, Priority.NORMAL, noon).isAllowed()).isTrue();
        assertThat(decide(r, optedOut, false, Channel.SMS, Category.SECURITY_ALERT, Priority.NORMAL, noon).isAllowed()).isTrue();
        assertThat(decide(r, optedOut, false, Channel.EMAIL, Category.SERVICE, Priority.NORMAL, noon).reason())
                .as("SERVICE honours the per-channel switch").isEqualTo(PolicyReason.CHANNEL_DISABLED);
        assertThat(decide(r, prefs(false, true, true, true), false, Channel.EMAIL, Category.SERVICE, Priority.NORMAL, noon).isAllowed())
                .isTrue();
        assertThat(decide(r, null, false, Channel.EMAIL, Category.SERVICE, Priority.NORMAL, noon).isAllowed())
                .as("no preferences: defaults allow service messages").isTrue();
    }

    // ------------------------------------------------------------ step 4: quiet hours

    @Test
    void marketingInsideQuietHoursIsDeferredToTheEndOfTheWindowInTheRecipientZone() {
        Recipient r = recipient("America/New_York");
        RecipientPreference p = prefs(true, true, true, true);
        // 22:30 local: deferred to 08:00 the next local day
        PolicyDecision late = decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL,
                at("America/New_York", "2026-06-10T22:30:00"));
        assertThat(late.isDeferred()).isTrue();
        assertThat(late.until()).isEqualTo(at("America/New_York", "2026-06-11T08:00:00"));
        // 07:59 local: deferred to 08:00 the same day
        PolicyDecision early = decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL,
                at("America/New_York", "2026-06-10T07:59:00"));
        assertThat(early.until()).isEqualTo(at("America/New_York", "2026-06-10T08:00:00"));
        // boundaries: 08:00 is open, 21:00 is closed
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL,
                at("America/New_York", "2026-06-10T08:00:00")).isAllowed()).isTrue();
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL,
                at("America/New_York", "2026-06-10T20:59:59")).isAllowed()).isTrue();
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL,
                at("America/New_York", "2026-06-10T21:00:00")).isDeferred()).isTrue();
    }

    @Test
    void theSameInstantIsQuietInOneZoneAndOpenInAnother() {
        RecipientPreference p = prefs(true, true, true, true);
        Instant instant = Instant.parse("2026-03-10T02:00:00Z"); // 22:00 EDT previous day, 11:00 in Tokyo
        assertThat(decide(recipient("America/New_York"), p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, instant)
                .isDeferred()).isTrue();
        assertThat(decide(recipient("Asia/Tokyo"), p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, instant)
                .isAllowed()).isTrue();
        assertThat(decide(recipient("Europe/London"), p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, instant)
                .isDeferred()).isTrue();
    }

    @Test
    void customQuietWindowsAndDisabledWindows() {
        Recipient r = recipient("UTC");
        RecipientPreference p = prefs(true, true, true, true);
        p.setQuietStart(LocalTime.of(22, 0));
        p.setQuietEnd(LocalTime.of(7, 0));
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, at("UTC", "2026-06-10T21:30:00"))
                .isAllowed()).as("21:30 is before this recipient's window").isTrue();
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, at("UTC", "2026-06-10T06:30:00"))
                .until()).isEqualTo(at("UTC", "2026-06-10T07:00:00"));
        // same-day window (no midnight crossing)
        p.setQuietStart(LocalTime.of(13, 0));
        p.setQuietEnd(LocalTime.of(15, 0));
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, at("UTC", "2026-06-10T14:00:00"))
                .until()).isEqualTo(at("UTC", "2026-06-10T15:00:00"));
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, at("UTC", "2026-06-10T15:00:00"))
                .isAllowed()).isTrue();
        // start == end disables quiet hours entirely
        p.setQuietStart(LocalTime.of(9, 0));
        p.setQuietEnd(LocalTime.of(9, 0));
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, at("UTC", "2026-06-10T03:00:00"))
                .isAllowed()).isTrue();
    }

    @Test
    void quietHoursApplicabilityMatrix() {
        Recipient r = recipient("UTC");
        RecipientPreference p = prefs(true, true, true, true);
        Instant night = at("UTC", "2026-06-10T23:00:00");
        // SECURITY_ALERT always sends
        for (Channel c : Channel.values()) {
            for (Priority pr : Priority.values()) {
                assertThat(decide(r, p, false, c, Category.SECURITY_ALERT, pr, night).isAllowed())
                        .as("security %s %s", c, pr).isTrue();
            }
        }
        // TRANSACTIONAL sends unless the channel is SMS/PUSH and the priority is LOW
        assertThat(decide(r, p, false, Channel.EMAIL, Category.TRANSACTIONAL, Priority.LOW, night).isAllowed()).isTrue();
        assertThat(decide(r, p, false, Channel.SMS, Category.TRANSACTIONAL, Priority.LOW, night).isDeferred()).isTrue();
        assertThat(decide(r, p, false, Channel.PUSH, Category.TRANSACTIONAL, Priority.LOW, night).isDeferred()).isTrue();
        assertThat(decide(r, p, false, Channel.SMS, Category.TRANSACTIONAL, Priority.NORMAL, night).isAllowed()).isTrue();
        // SERVICE defers LOW and NORMAL, sends HIGH and CRITICAL
        assertThat(decide(r, p, false, Channel.EMAIL, Category.SERVICE, Priority.LOW, night).isDeferred()).isTrue();
        assertThat(decide(r, p, false, Channel.EMAIL, Category.SERVICE, Priority.NORMAL, night).isDeferred()).isTrue();
        assertThat(decide(r, p, false, Channel.EMAIL, Category.SERVICE, Priority.HIGH, night).isAllowed()).isTrue();
        assertThat(decide(r, p, false, Channel.SMS, Category.SERVICE, Priority.CRITICAL, night).isAllowed()).isTrue();
        // MARKETING always defers inside the window, whatever the priority
        for (Priority pr : Priority.values()) {
            assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, pr, night).isDeferred()).as(pr.name()).isTrue();
        }
    }

    @Test
    void springForwardGapEndTimeResolvesForwardAndStaysInTheFuture() {
        // New York 2026-03-08: clocks jump 02:00 -> 03:00, so 02:30 does not exist.
        Recipient r = recipient("America/New_York");
        RecipientPreference p = prefs(true, true, true, true);
        p.setQuietStart(LocalTime.of(0, 0));
        p.setQuietEnd(LocalTime.of(2, 30));
        Instant now = Instant.parse("2026-03-08T05:30:00Z"); // 00:30 EST
        PolicyDecision d = decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, now);
        assertThat(d.isDeferred()).isTrue();
        assertThat(d.until()).isAfter(now).isEqualTo(Instant.parse("2026-03-08T07:30:00Z")); // 03:30 EDT
    }

    @Test
    void fallBackOverlapUsesTheLocalWallClockOfTheEndTime() {
        // New York 2026-11-01: 01:30 happens twice, clocks go 02:00 EDT -> 01:00 EST.
        Recipient r = recipient("America/New_York");
        RecipientPreference p = prefs(true, true, true, true);
        Instant firstOneThirty = Instant.parse("2026-11-01T05:30:00Z"); // 01:30 EDT
        PolicyDecision d = decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, firstOneThirty);
        assertThat(d.until()).isEqualTo(Instant.parse("2026-11-01T13:00:00Z")); // 08:00 EST
        Instant secondOneThirty = Instant.parse("2026-11-01T06:30:00Z"); // 01:30 EST
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, secondOneThirty).until())
                .isEqualTo(Instant.parse("2026-11-01T13:00:00Z"));
        // a window ending inside the repeated hour must never yield a past instant
        p.setQuietStart(LocalTime.of(0, 0));
        p.setQuietEnd(LocalTime.of(1, 15));
        PolicyDecision overlap = decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, secondOneThirty);
        assertThat(overlap.isDeferred() ? overlap.until().isAfter(secondOneThirty) : true).isTrue();
    }

    @Test
    void invalidTimeZoneFailsClosedForMarketingAndOpenForOthers() {
        Recipient r = recipient("Mars/Olympus_Mons");
        RecipientPreference p = prefs(true, true, true, true);
        Instant any = Instant.parse("2026-06-10T03:00:00Z");
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, any).reason())
                .isEqualTo(PolicyReason.INVALID_PREFERENCES);
        assertThat(decide(r, p, false, Channel.SMS, Category.TRANSACTIONAL, Priority.LOW, any).isAllowed()).isTrue();
        assertThat(decide(r, p, false, Channel.EMAIL, Category.SERVICE, Priority.LOW, any).isAllowed()).isTrue();
        r.setTimeZone(null);
        assertThat(decide(r, p, false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, any).reason())
                .isEqualTo(PolicyReason.INVALID_PREFERENCES);
        // consent is checked before the zone: an opted-out recipient is OPTED_OUT, not INVALID_PREFERENCES
        assertThat(decide(r, prefs(false, true, true, true), false, Channel.EMAIL, Category.MARKETING, Priority.NORMAL, any).reason())
                .isEqualTo(PolicyReason.OPTED_OUT);
    }

    // ------------------------------------------------------------ step 5: fallback never bypasses consent

    @Test
    void fallbackChannelsAreEvaluatedIndependentlyThroughTheFullPolicy() {
        RecipientPreferenceRepository prefRepo = mock(RecipientPreferenceRepository.class);
        SuppressionRepository supRepo = mock(SuppressionRepository.class);
        Recipient r = recipient("America/New_York");
        // marketing opt-in, but SMS disabled and the push address is suppressed
        RecipientPreference p = prefs(true, true, false, true);
        when(prefRepo.findById("rcp-test")).thenReturn(Optional.of(p));
        when(supRepo.existsByTenantIdAndChannelAndAddressHash(any(), any(), any())).thenReturn(false);
        when(supRepo.existsByTenantIdAndChannelAndAddressHash(eq("tenant-retail"), eq(Channel.PUSH),
                eq(Ids.addressHash("push-token-1")))).thenReturn(true);
        PolicyService service = new PolicyService(prefRepo, supRepo);

        List<PolicyService.FallbackDecision> out = service.evaluateFallbacks(r, List.of(Channel.SMS, Channel.PUSH,
                Channel.EMAIL), Category.MARKETING, Priority.NORMAL, NOON_NY);

        assertThat(out).extracting(PolicyService.FallbackDecision::channel).containsExactly(Channel.SMS, Channel.PUSH, Channel.EMAIL);
        assertThat(out.get(0).decision().reason()).as("SMS disabled -> marketing fallback not allowed").isEqualTo(PolicyReason.OPTED_OUT);
        assertThat(out.get(1).decision().reason()).as("suppressed fallback address").isEqualTo(PolicyReason.SUPPRESSED);
        assertThat(out.get(2).decision().isAllowed()).isTrue();
    }

    @Test
    void fallbackDuringQuietHoursIsDeferredNotSentImmediately() {
        RecipientPreferenceRepository prefRepo = mock(RecipientPreferenceRepository.class);
        SuppressionRepository supRepo = mock(SuppressionRepository.class);
        when(prefRepo.findById("rcp-test")).thenReturn(Optional.of(prefs(true, true, true, true)));
        PolicyService service = new PolicyService(prefRepo, supRepo);
        Instant night = at("America/New_York", "2026-06-10T23:00:00");
        List<PolicyService.FallbackDecision> out = service.evaluateFallbacks(recipient("America/New_York"),
                List.of(Channel.SMS), Category.MARKETING, Priority.HIGH, night);
        assertThat(out.get(0).decision().isDeferred()).isTrue();
        assertThat(out.get(0).decision().until()).isEqualTo(at("America/New_York", "2026-06-11T08:00:00"));
    }

    @Test
    void evaluateLoadsPreferencesAndSuppressionForThePrimaryChannel() {
        RecipientPreferenceRepository prefRepo = mock(RecipientPreferenceRepository.class);
        SuppressionRepository supRepo = mock(SuppressionRepository.class);
        when(prefRepo.findById("rcp-test")).thenReturn(Optional.empty());
        when(supRepo.existsByTenantIdAndChannelAndAddressHash("tenant-retail", Channel.EMAIL,
                Ids.addressHash("jane@example.test"))).thenReturn(true);
        PolicyService service = new PolicyService(prefRepo, supRepo);
        assertThat(service.evaluate(recipient("UTC"), Channel.EMAIL, Category.TRANSACTIONAL, Priority.NORMAL, NOON_NY).reason())
                .isEqualTo(PolicyReason.SUPPRESSED);
        assertThat(service.evaluate(recipient("UTC"), Channel.SMS, Category.TRANSACTIONAL, Priority.NORMAL, NOON_NY).isAllowed())
                .isTrue();
    }

    @Test
    void quietHoursEndHelperIsNullOutsideTheWindow() {
        assertThat(PolicyService.quietHoursEnd(Instant.parse("2026-06-10T12:00:00Z"), ZoneId.of("UTC"),
                LocalTime.of(21, 0), LocalTime.of(8, 0))).isNull();
    }
}
