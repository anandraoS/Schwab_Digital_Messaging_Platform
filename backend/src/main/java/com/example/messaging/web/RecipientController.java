package com.example.messaging.web;

import com.example.messaging.common.PageResponse;
import com.example.messaging.domain.Channel;
import com.example.messaging.service.PreferenceService;
import com.example.messaging.web.dto.RecipientDtos.PreferencesResponse;
import com.example.messaging.web.dto.RecipientDtos.RecipientSummary;
import com.example.messaging.web.dto.RecipientDtos.SuppressionRequest;
import com.example.messaging.web.dto.RecipientDtos.SuppressionResponse;
import com.example.messaging.web.dto.RecipientDtos.UpdatePreferencesRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/recipients", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Recipients")
public class RecipientController {

    private static final Set<String> SORTABLE = Set.of("externalRef", "displayName", "createdAt");

    private final PreferenceService preferences;

    public RecipientController(PreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','TENANT_ADMIN','PLATFORM_ADMIN','SENDER')")
    public PageResponse<RecipientSummary> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(preferences.search(q,
                Pagination.of(page, size, sort, SORTABLE, "externalRef", Sort.Direction.ASC)));
    }

    @GetMapping("/{id}/preferences")
    @PreAuthorize("hasAnyRole('OPERATOR','TENANT_ADMIN','PLATFORM_ADMIN','SENDER')")
    public PreferencesResponse getPreferences(@PathVariable String id) {
        return preferences.get(id);
    }

    @PutMapping(value = "/{id}/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','OPERATOR','PLATFORM_ADMIN')")
    @Operation(summary = "Update preferences; a stale body.version yields 409 CONFLICT_OPTIMISTIC_LOCK")
    public PreferencesResponse updatePreferences(@PathVariable String id, @Valid @RequestBody UpdatePreferencesRequest req) {
        return preferences.update(id, req);
    }

    @PostMapping(value = "/{id}/suppressions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<SuppressionResponse> addSuppression(@PathVariable String id,
            @Valid @RequestBody SuppressionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(preferences.addSuppression(id, req));
    }

    @DeleteMapping("/{id}/suppressions/{channel}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','OPERATOR')")
    public ResponseEntity<Void> removeSuppression(@PathVariable String id, @PathVariable Channel channel) {
        preferences.removeSuppression(id, channel);
        return ResponseEntity.noContent().build();
    }
}
