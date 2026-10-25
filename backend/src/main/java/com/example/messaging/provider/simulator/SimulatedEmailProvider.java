package com.example.messaging.provider.simulator;

import com.example.messaging.config.SimulatorProperties;
import com.example.messaging.provider.EmailProvider;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Simulated email vendor ({@code simulated-email}). */
@Component
public class SimulatedEmailProvider extends SimulatedProviderBase implements EmailProvider {

    public static final String ID = "simulated-email";

    public SimulatedEmailProvider(FaultInjector faults, SimulatorProperties props, Clock clock,
            ObjectProvider<CallbackEmitter> emitter) {
        super(faults, props, clock, emitter);
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    protected String idPrefix() {
        return "em-";
    }
}
