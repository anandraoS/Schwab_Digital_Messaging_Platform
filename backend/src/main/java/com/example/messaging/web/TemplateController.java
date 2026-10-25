package com.example.messaging.web;

import com.example.messaging.common.ApiException;
import com.example.messaging.common.PageResponse;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.TemplateStatus;
import com.example.messaging.service.AuditService;
import com.example.messaging.service.TemplateService;
import com.example.messaging.web.dto.AuditDtos.AuditEventView;
import com.example.messaging.web.dto.TemplateDtos.CreateTemplateRequest;
import com.example.messaging.web.dto.TemplateDtos.CreateVersionRequest;
import com.example.messaging.web.dto.TemplateDtos.PreviewRequest;
import com.example.messaging.web.dto.TemplateDtos.PreviewResponse;
import com.example.messaging.web.dto.TemplateDtos.RejectRequest;
import com.example.messaging.web.dto.TemplateDtos.TemplateDetail;
import com.example.messaging.web.dto.TemplateDtos.TemplateSummary;
import com.example.messaging.web.dto.TemplateDtos.UpdateVersionRequest;
import com.example.messaging.web.dto.TemplateDtos.VersionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/templates", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Templates")
public class TemplateController {

    private static final Set<String> SORTABLE = Set.of("code", "name", "createdAt", "updatedAt", "channel");

    private final TemplateService templates;
    private final AuditService audit;

    public TemplateController(TemplateService templates, AuditService audit) {
        this.templates = templates;
        this.audit = audit;
    }

    @GetMapping
    public PageResponse<TemplateSummary> list(@RequestParam(required = false) Channel channel,
            @RequestParam(required = false) TemplateStatus status, @RequestParam(required = false) String code,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(templates.list(channel, status, code,
                Pagination.of(page, size, sort, SORTABLE, "code", Sort.Direction.ASC)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('TEMPLATE_AUTHOR','TENANT_ADMIN')")
    @Operation(summary = "Create a template with its first DRAFT version")
    public ResponseEntity<TemplateDetail> create(@Valid @RequestBody CreateTemplateRequest req) {
        TemplateDetail d = templates.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/templates/" + d.id())).body(d);
    }

    @GetMapping("/{id}")
    public TemplateDetail get(@PathVariable String id) {
        return templates.get(id);
    }

    @PostMapping(value = "/{id}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('TEMPLATE_AUTHOR','TENANT_ADMIN')")
    public ResponseEntity<VersionView> createVersion(@PathVariable String id, @Valid @RequestBody CreateVersionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templates.createVersion(id, req));
    }

    @PutMapping(value = "/{id}/versions/{versionNo}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('TEMPLATE_AUTHOR')")
    @Operation(summary = "Edit a DRAFT version (optimistic lock via body.version, optionally mirrored in If-Match)")
    public VersionView edit(@PathVariable String id, @PathVariable int versionNo,
            @RequestParam(required = false, defaultValue = "en") String locale,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateVersionRequest req) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            String token = ifMatch.replace("W/", "").replace("\"", "").trim();
            if (!token.equals(String.valueOf(req.version()))) {
                throw new ApiException.ValidationException("If-Match", "If-Match does not match body.version");
            }
        }
        return templates.edit(id, versionNo, locale, req);
    }

    @PostMapping("/{id}/versions/{versionNo}/submit")
    @PreAuthorize("hasRole('TEMPLATE_AUTHOR')")
    public VersionView submit(@PathVariable String id, @PathVariable int versionNo,
            @RequestParam(required = false, defaultValue = "en") String locale) {
        return templates.submit(id, versionNo, locale);
    }

    @PostMapping("/{id}/versions/{versionNo}/approve")
    @PreAuthorize("hasRole('TEMPLATE_APPROVER')")
    @Operation(summary = "Approve and publish (four-eyes: approver must differ from author)")
    public VersionView approve(@PathVariable String id, @PathVariable int versionNo,
            @RequestParam(required = false, defaultValue = "en") String locale) {
        return templates.approve(id, versionNo, locale);
    }

    @PostMapping(value = "/{id}/versions/{versionNo}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('TEMPLATE_APPROVER')")
    public VersionView reject(@PathVariable String id, @PathVariable int versionNo,
            @RequestParam(required = false, defaultValue = "en") String locale,
            @Valid @RequestBody RejectRequest req) {
        return templates.reject(id, versionNo, locale, req.comment());
    }

    @PostMapping("/{id}/versions/{versionNo}/retire")
    @PreAuthorize("hasAnyRole('TEMPLATE_APPROVER','TENANT_ADMIN')")
    public VersionView retire(@PathVariable String id, @PathVariable int versionNo,
            @RequestParam(required = false, defaultValue = "en") String locale) {
        return templates.retire(id, versionNo, locale);
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('TEMPLATE_AUTHOR','TENANT_ADMIN','OPERATOR')")
    @Operation(summary = "Render a template with sample data (validation only, nothing is sent or stored)")
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest req) {
        return templates.preview(req);
    }

    @GetMapping("/{id}/history")
    public List<AuditEventView> history(@PathVariable String id) {
        return templates.history(id).stream().map(a -> new AuditEventView(a.getId(), a.getTenantId(), a.getActor(),
                a.getActorRole(), a.getAction(), a.getEntityType(), a.getEntityId(), a.getCorrelationId(),
                audit.parseDetail(a.getDetailJson()), a.getOccurredAt())).toList();
    }
}
