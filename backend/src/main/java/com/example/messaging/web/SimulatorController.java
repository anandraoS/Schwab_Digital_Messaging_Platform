package com.example.messaging.web;

import com.example.messaging.provider.simulator.CallbackEmitter;
import com.example.messaging.web.dto.OpsDtos.SimulatorEmitRequest;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** DEV ONLY (profiles local/test): makes the provider simulator post a signed callback to the platform itself. */
@RestController
@Hidden
@Profile({"local", "test"})
@RequestMapping(value = "/dev/simulator", produces = MediaType.APPLICATION_JSON_VALUE)
public class SimulatorController {

    private final CallbackEmitter emitter;

    public SimulatorController(CallbackEmitter emitter) {
        this.emitter = emitter;
    }

    @PostMapping(value = "/callbacks/emit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, List<CallbackEmitter.Emitted>> emit(@Valid @RequestBody SimulatorEmitRequest body) {
        List<CallbackEmitter.Emitted> scheduled = emitter.emit(body.messageId(), body.type(),
                body.delayMs() == null ? 0 : body.delayMs(), Boolean.TRUE.equals(body.duplicate()),
                Boolean.TRUE.equals(body.outOfOrder()));
        return Map.of("scheduled", scheduled);
    }
}
