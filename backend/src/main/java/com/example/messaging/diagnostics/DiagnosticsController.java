package com.example.messaging.diagnostics;

import com.example.messaging.common.ApiException;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.security.AuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provider diagnostics documents of one message (redacted callbacks and call summaries). */
@RestController
@RequestMapping(value = "/api/v1/diagnostics", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Diagnostics")
public class DiagnosticsController {

    private final MessageRequestRepository messages;
    private final ProviderDiagnosticsStore store;
    private final AuthorizationService authz;

    public DiagnosticsController(MessageRequestRepository messages, ProviderDiagnosticsStore store,
            AuthorizationService authz) {
        this.messages = messages;
        this.store = store;
        this.authz = authz;
    }

    @GetMapping("/messages/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR','PLATFORM_ADMIN')")
    @Operation(summary = "Provider diagnostics documents for a message (tenant checked, PII redacted)")
    public List<Map<String, Object>> forMessage(@PathVariable String id) {
        MessageRequest m = messages.findById(id).orElseThrow(() -> new ApiException.NotFoundException("Message"));
        authz.assertTenantAccess(m.getTenantId(), "Message");
        try {
            return store.findByMessageId(id);
        } catch (RuntimeException e) {
            throw new ApiException.ServiceUnavailableException("Diagnostics store is unavailable", 5);
        }
    }
}
