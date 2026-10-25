package com.example.messaging.provider.simulator;

import com.example.messaging.config.SimulatorProperties;
import com.example.messaging.provider.SmsProvider;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Simulated SMS vendor ({@code simulated-sms}). */
@Component
public class SimulatedSmsProvider extends SimulatedProviderBase implements SmsProvider {

    public static final String ID = "simulated-sms";

    public SimulatedSmsProvider(FaultInjector faults, SimulatorProperties props, Clock clock,
            ObjectProvider<CallbackEmitter> emitter) {
        super(faults, props, clock, emitter);
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    protected String idPrefix() {
        return "sm-";
    }
}
