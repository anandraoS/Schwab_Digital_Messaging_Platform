package com.example.messaging.provider.simulator;

import com.example.messaging.config.SimulatorProperties;
import com.example.messaging.provider.SmsProvider;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Second SMS vendor, the worked "new provider" example of ADR-0002 / spec 0002. It has its own id, message id format
 * and callback payload shape (see {@code AcmeSmsPayloadMapper}) and is switched on purely by configuration
 * ({@code provider_config.enabled}, seeded disabled); no orchestration code mentions it.
 */
@Component
public class AcmeSmsProvider extends SimulatedProviderBase implements SmsProvider {

    public static final String ID = "acme-sms";

    public AcmeSmsProvider(FaultInjector faults, SimulatorProperties props, Clock clock,
            ObjectProvider<CallbackEmitter> emitter) {
        super(faults, props, clock, emitter);
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    protected String idPrefix() {
        return "acme-";
    }
}
