package com.example.messaging.web;

import com.example.messaging.common.ApiException;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.provider.ProviderRegistry;
import com.example.messaging.provider.simulator.FaultInjector;
import com.example.messaging.provider.simulator.SimulatedProviderBase;
import com.example.messaging.service.AuditService;
import com.example.messaging.web.dto.OpsDtos.FaultModeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Simulator fault injection. The bean exists only in profiles local and test: in any other profile the route is 404. */
@RestController
@Profile({"local", "test"})
@RequestMapping(value = "/api/v1/providers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Providers")
public class ProviderFaultController {

    private final FaultInjector faults;
    private final ProviderRegistry registry;
    private final AuditService audit;

    public ProviderFaultController(FaultInjector faults, ProviderRegistry registry, AuditService audit) {
        this.faults = faults;
        this.registry = registry;
        this.audit = audit;
    }

    @PutMapping(value = "/{providerId}/fault-mode", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Set the simulator fault mode (profiles local/test only)")
    public FaultModeRequest set(@PathVariable String providerId, @Valid @RequestBody FaultModeRequest body) {
        if (!(registry.adapter(providerId).orElseThrow(() -> new ApiException.NotFoundException("Provider"))
                instanceof SimulatedProviderBase)) {
            throw new ApiException.ValidationException("providerId", "only simulated providers accept a fault mode");
        }
        double probability = body.probability() == null ? 1.0 : body.probability();
        faults.set(providerId, body.mode(), probability);
        audit.record(null, AuditAction.PROVIDER_CONFIG_UPDATED, "PROVIDER", providerId,
                Map.of("faultMode", body.mode().name(), "probability", probability));
        return new FaultModeRequest(body.mode(), probability);
    }
}
