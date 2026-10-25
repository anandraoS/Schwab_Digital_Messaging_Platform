package com.example.messaging.web;

import com.example.messaging.provider.ProviderAdminService;
import com.example.messaging.web.dto.OpsDtos.ProviderConfigRequest;
import com.example.messaging.web.dto.OpsDtos.ProviderHealth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provider health and configuration. */
@RestController
@RequestMapping(value = "/api/v1/providers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Providers")
public class ProviderController {

    private final ProviderAdminService providers;

    public ProviderController(ProviderAdminService providers) {
        this.providers = providers;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','TENANT_ADMIN','PLATFORM_ADMIN')")
    @Operation(summary = "Provider health: circuit state, success rate, p95 latency, last error")
    public List<ProviderHealth> list() {
        return providers.list();
    }

    @PutMapping(value = "/{providerId}/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Enable/disable a provider, change rate limit and timeout")
    public ProviderHealth updateConfig(@PathVariable String providerId, @Valid @RequestBody ProviderConfigRequest body) {
        return providers.update(providerId, body);
    }
}
