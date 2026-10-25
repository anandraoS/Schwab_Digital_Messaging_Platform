package com.example.messaging.web;

import com.example.messaging.security.CurrentPrincipal;
import com.example.messaging.security.TenantContext;
import com.example.messaging.web.dto.AuditDtos.MeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Identity")
public class MeController {

    private final TenantContext tenantContext;

    public MeController(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @GetMapping
    @Operation(summary = "Authenticated principal: roles and tenant (drives the role-aware UI)")
    public MeResponse me() {
        CurrentPrincipal p = tenantContext.requirePrincipal();
        return new MeResponse(p.subject(), p.tenant(), tenantContext.scopeTenant(),
                p.roles().stream().sorted().toList(), p.scopes().stream().sorted().toList(), p.isPlatformAdmin());
    }
}
