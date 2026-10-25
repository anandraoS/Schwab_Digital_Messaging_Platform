package com.example.messaging.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.messaging.provider.simulator.FaultInjector;
import com.example.messaging.provider.simulator.FaultInjector.Fault;
import com.example.messaging.provider.simulator.FaultInjector.Mode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaultInjectorTest {

    private List<Fault> draw(FaultInjector f, String provider, int n) {
        List<Fault> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(f.next(provider));
        }
        return out;
    }

    @Test
    void withoutConfigurationEveryCallSucceeds() {
        FaultInjector f = new FaultInjector(1L);
        assertThat(draw(f, "p", 20)).containsOnly(Fault.NONE);
        assertThat(f.get("p").mode()).isEqualTo(Mode.NONE);
    }

    @Test
    void everyModeWithProbabilityOneProducesItsFault() {
        FaultInjector f = new FaultInjector(1L);
        for (Mode m : List.of(Mode.TRANSIENT, Mode.PERMANENT, Mode.RATE_LIMIT, Mode.TIMEOUT, Mode.AMBIGUOUS)) {
            f.set("p", m, 1.0);
            assertThat(draw(f, "p", 5)).as(m.name()).containsOnly(Fault.valueOf(m.name()));
        }
        f.set("p", Mode.NONE, 1.0);
        assertThat(draw(f, "p", 5)).containsOnly(Fault.NONE);
    }

    @Test
    void probabilityZeroNeverFaults() {
        FaultInjector f = new FaultInjector(1L);
        f.set("p", Mode.PERMANENT, 0.0);
        assertThat(draw(f, "p", 200)).containsOnly(Fault.NONE);
    }

    @Test
    void alternatingFailsEveryOtherCallStartingWithTheFirstAndIgnoresProbability() {
        FaultInjector f = new FaultInjector(1L);
        f.set("p", Mode.ALTERNATING, 0.0);
        assertThat(draw(f, "p", 6)).containsExactly(Fault.TRANSIENT, Fault.NONE, Fault.TRANSIENT, Fault.NONE,
                Fault.TRANSIENT, Fault.NONE);
        f.set("p", Mode.ALTERNATING, 1.0); // resetting the mode restarts the pattern
        assertThat(draw(f, "p", 2)).containsExactly(Fault.TRANSIENT, Fault.NONE);
    }

    @Test
    void aSeedMakesProbabilisticFaultsReproducibleAndRoughlyProportional() {
        FaultInjector a = new FaultInjector(42L);
        FaultInjector b = new FaultInjector(42L);
        a.set("p", Mode.TRANSIENT, 0.3);
        b.set("p", Mode.TRANSIENT, 0.3);
        List<Fault> first = draw(a, "p", 1000);
        assertThat(draw(b, "p", 1000)).isEqualTo(first);
        long faults = first.stream().filter(x -> x == Fault.TRANSIENT).count();
        assertThat(faults).isBetween(220L, 380L);
        FaultInjector other = new FaultInjector(43L);
        other.set("p", Mode.TRANSIENT, 0.3);
        assertThat(draw(other, "p", 1000)).isNotEqualTo(first);
    }

    @Test
    void providersAreIndependentAndResetClearsEverything() {
        FaultInjector f = new FaultInjector(1L);
        f.set("a", Mode.PERMANENT, 1.0);
        assertThat(f.next("a")).isEqualTo(Fault.PERMANENT);
        assertThat(f.next("b")).isEqualTo(Fault.NONE);
        f.reset();
        assertThat(f.next("a")).isEqualTo(Fault.NONE);
        assertThat(f.get("a").probability()).isEqualTo(1.0);
    }

    @Test
    void invalidProbabilitiesAreRejected() {
        FaultInjector f = new FaultInjector(1L);
        assertThatThrownBy(() -> f.set("p", Mode.TRANSIENT, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.set("p", Mode.TRANSIENT, 1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.set("p", Mode.TRANSIENT, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        f.set("p", null, 0.5); // a missing mode means NONE
        assertThat(f.get("p").mode()).isEqualTo(Mode.NONE);
    }
}
