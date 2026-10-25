package com.example.messaging.provider.simulator;

import com.example.messaging.config.SimulatorProperties;
import com.example.messaging.provider.PushNotificationProvider;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Simulated push vendor ({@code simulated-push}). */
@Component
public class SimulatedPushProvider extends SimulatedProviderBase implements PushNotificationProvider {

    public static final String ID = "simulated-push";

    public SimulatedPushProvider(FaultInjector faults, SimulatorProperties props, Clock clock,
            ObjectProvider<CallbackEmitter> emitter) {
        super(faults, props, clock, emitter);
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    protected String idPrefix() {
        return "pn-";
    }
}
