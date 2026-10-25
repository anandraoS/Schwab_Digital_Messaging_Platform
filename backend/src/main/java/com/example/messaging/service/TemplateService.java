package com.example.messaging.service;

import com.example.messaging.common.ApiError;
import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.common.Ids;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.AuditEvent;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Template;
import com.example.messaging.domain.TemplateStatus;
import com.example.messaging.domain.TemplateVersion;
import com.example.messaging.repository.Specs;
import com.example.messaging.repository.TemplateRepository;
import com.example.messaging.repository.TemplateVersionRepository;
import com.example.messaging.security.AuthorizationService;
import com.example.messaging.security.TenantContext;
import com.example.messaging.template.RenderedContent;
import com.example.messaging.template.TemplateRenderException;
import com.example.messaging.template.TemplateRenderer;
import com.example.messaging.template.VariableValidator;
import com.example.messaging.web.dto.TemplateDtos.CreateTemplateRequest;
import com.example.messaging.web.dto.TemplateDtos.CreateVersionRequest;
import com.example.messaging.web.dto.TemplateDtos.PreviewRequest;
import com.example.messaging.web.dto.TemplateDtos.PreviewResponse;
import com.example.messaging.web.dto.TemplateDtos.TemplateDetail;
import com.example.messaging.web.dto.TemplateDtos.TemplateSummary;
import com.example.messaging.web.dto.TemplateDtos.UpdateVersionRequest;
import com.example.messaging.web.dto.TemplateDtos.VersionView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Subquery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Template authoring lifecycle: DRAFT -> PENDING_APPROVAL -> PUBLISHED -> RETIRED with four-eyes approval.
 * Published content is immutable (edits create new versions); unpinned sends pin the latest PUBLISHED at accept time.
 */
@Service
public class TemplateService {

    /** A template version resolved for sending. */
    public record ResolvedTemplate(Template template, TemplateVersion version, Set<String> declaredVariables) {}

    private final TemplateRepository templates;
    private final TemplateVersionRepository versions;
    private final TemplateRenderer renderer;
    private final VariableValidator variableValidator;
    private final AuditService audit;
    private final AuthorizationService authz;
    private final TenantContext tenantContext;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TemplateService(TemplateRepository templates, TemplateVersionRepository versions, TemplateRenderer renderer,
            VariableValidator variableValidator, AuditService audit, AuthorizationService authz,
            TenantContext tenantContext, ObjectMapper mapper, Clock clock) {
        this.templates = templates;
        this.versions = versions;
        this.renderer = renderer;
        this.variableValidator = variableValidator;
        this.audit = audit;
        this.authz = authz;
        this.tenantContext = tenantContext;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<TemplateSummary> list(Channel channel, TemplateStatus status, String code, Pageable pageable) {
        Specification<Template> spec = Specification.<Template>where(Specs.eq("tenantId", tenantContext.scopeTenant()))
                .and(Specs.eq("channel", channel))
                .and(Specs.containsAny(code, "code"))
                .and(status == null ? null : hasVersionWithStatus(status));
        Page<Template> page = templates.findAll(spec, pageable);
        // one extra query for the whole page instead of one per template (no N+1)
        Map<String, List<TemplateVersion>> byTemplate = page.isEmpty() ? Map.of()
                : versions.findByTemplateIdIn(page.getContent().stream().map(Template::getId).toList()).stream()
                        .collect(Collectors.groupingBy(TemplateVersion::getTemplateId));
        return page.map(t -> toSummary(t, byTemplate.getOrDefault(t.getId(), List.of())));
    }

    private static Specification<Template> hasVersionWithStatus(TemplateStatus status) {
        return (root, query, cb) -> {
            Subquery<String> sub = query.subquery(String.class);
            var v = sub.from(TemplateVersion.class);
            sub.select(v.get("id")).where(cb.equal(v.get("templateId"), root.get("id")),
                    cb.equal(v.get("status"), status));
            return cb.exists(sub);
        };
    }

    @Transactional(readOnly = true)
    public TemplateDetail get(String templateId) {
        Template t = requireTemplate(templateId);
        return toDetail(t, versions.findByTemplateIdOrderByVersionNoAscLocaleAsc(templateId));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> history(String templateId) {
        requireTemplate(templateId);
        return audit.search(null, "TEMPLATE", templateId, null, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 200,
                        org.springframework.data.domain.Sort.by("occurredAt").ascending())).getContent();
    }

    // ------------------------------------------------------------------ authoring

    @Transactional
    public TemplateDetail create(CreateTemplateRequest req) {
        String tenantId = tenantContext.requireTenant();
        if (templates.findByTenantIdAndCodeAndChannel(tenantId, req.code(), req.channel()).isPresent()) {
            throw new ApiException.ConflictException(ErrorCode.DUPLICATE_RESOURCE,
                    "A template with this code and channel already exists");
        }
        List<String> declared = normalizeVariables(req.variables());
        validateContent(req.channel(), req.subject(), req.body(), declared);
        Instant now = clock.instant();
        Template t = new Template();
        t.setId(Ids.newId());
        t.setTenantId(tenantId);
        t.setCode(req.code());
        t.setName(req.name());
        t.setChannel(req.channel());
        t.setCategory(req.category());
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        try {
            templates.saveAndFlush(t);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException.ConflictException(ErrorCode.DUPLICATE_RESOURCE,
                    "A template with this code and channel already exists");
        }
        TemplateVersion v = newVersion(t, 1, normalizeLocale(req.locale()), req.subject(), req.body(), declared, now);
        versions.save(v);
        audit.record(tenantId, AuditAction.TEMPLATE_CREATED, "TEMPLATE", t.getId(),
                Map.of("code", t.getCode(), "channel", t.getChannel().name(), "versionNo", 1, "locale", v.getLocale()));
        return toDetail(t, List.of(v));
    }

    @Transactional
    public VersionView createVersion(String templateId, CreateVersionRequest req) {
        Template t = requireTemplate(templateId);
        String locale = normalizeLocale(req.locale());
        int max = versions.maxVersionNo(templateId);
        int versionNo = req.versionNo() == null ? max + 1 : req.versionNo();
        if (versionNo < 1 || versionNo > max + 1) {
            throw new ApiException.ValidationException("versionNo", "versionNo must be between 1 and " + (max + 1));
        }
        if (versions.findByTemplateIdAndVersionNoAndLocale(templateId, versionNo, locale).isPresent()) {
            throw new ApiException.ConflictException(ErrorCode.DUPLICATE_RESOURCE,
                    "Version " + versionNo + " already exists for locale " + locale);
        }
        List<String> declared = normalizeVariables(req.variables());
        validateContent(t.getChannel(), req.subject(), req.body(), declared);
        TemplateVersion v = newVersion(t, versionNo, locale, req.subject(), req.body(), declared, clock.instant());
        versions.save(v);
        touch(t);
        audit.record(t.getTenantId(), AuditAction.TEMPLATE_VERSION_CREATED, "TEMPLATE", t.getId(),
                Map.of("versionNo", versionNo, "locale", locale));
        return toView(v);
    }

    @Transactional
    public VersionView edit(String templateId, int versionNo, String locale, UpdateVersionRequest req) {
        Template t = requireTemplate(templateId);
        TemplateVersion v = requireVersion(templateId, versionNo, locale);
        if (v.getStatus() != TemplateStatus.DRAFT) {
            throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Only DRAFT versions can be edited (current status " + v.getStatus() + ")");
        }
        if (!req.version().equals(v.getVersion())) {
            throw new ApiException.ConflictException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK,
                    "Template version was modified by someone else; reload and retry");
        }
        List<String> declared = normalizeVariables(req.variables());
        validateContent(t.getChannel(), req.subject(), req.body(), declared);
        v.setSubject(blankToNull(req.subject()));
        v.setBody(req.body());
        v.setVariablesJson(writeVariables(declared));
        // the editor becomes the author of record, so four-eyes approval cannot be bypassed by editing
        v.setCreatedBy(authz.actor());
        v.setUpdatedAt(clock.instant());
        TemplateVersion saved = versions.saveAndFlush(v);
        touch(t);
        audit.record(t.getTenantId(), AuditAction.TEMPLATE_VERSION_EDITED, "TEMPLATE", t.getId(),
                Map.of("versionNo", versionNo, "locale", v.getLocale()));
        return toView(saved);
    }

    @Transactional
    public VersionView submit(String templateId, int versionNo, String locale) {
        Template t = requireTemplate(templateId);
        TemplateVersion v = requireVersion(templateId, versionNo, locale);
        requireStatus(v, TemplateStatus.DRAFT, "submit");
        // content may have been created before rule changes: validate again before review
        validateContent(t.getChannel(), v.getSubject(), v.getBody(), readVariables(v));
        v.setStatus(TemplateStatus.PENDING_APPROVAL);
        v.setRejectionComment(null);
        v.setUpdatedAt(clock.instant());
        versions.saveAndFlush(v);
        audit.record(t.getTenantId(), AuditAction.TEMPLATE_SUBMITTED, "TEMPLATE", t.getId(),
                Map.of("versionNo", versionNo, "locale", v.getLocale()));
        return toView(v);
    }

    /** Four-eyes: the approver must differ from the author of record. Retires the previous PUBLISHED version. */
    @Transactional
    public VersionView approve(String templateId, int versionNo, String locale) {
        Template t = requireTemplate(templateId);
        TemplateVersion v = requireVersion(templateId, versionNo, locale);
        requireStatus(v, TemplateStatus.PENDING_APPROVAL, "approve");
        String approver = authz.actor();
        if (approver.equals(v.getCreatedBy())) {
            throw new ApiException.ForbiddenException("Four-eyes rule: the author of a template version cannot approve it");
        }
        Instant now = clock.instant();
        for (TemplateVersion prev : versions.findByTemplateIdAndLocaleAndStatus(templateId, v.getLocale(),
                TemplateStatus.PUBLISHED)) {
            prev.setStatus(TemplateStatus.RETIRED);
            prev.setUpdatedAt(now);
            versions.save(prev);
            audit.record(t.getTenantId(), AuditAction.TEMPLATE_RETIRED, "TEMPLATE", t.getId(),
                    Map.of("versionNo", prev.getVersionNo(), "locale", prev.getLocale(), "reason", "superseded"));
        }
        v.setStatus(TemplateStatus.PUBLISHED);
        v.setApprovedBy(approver);
        v.setPublishedAt(now);
        v.setUpdatedAt(now);
        versions.saveAndFlush(v);
        touch(t);
        audit.record(t.getTenantId(), AuditAction.TEMPLATE_APPROVED, "TEMPLATE", t.getId(),
                Map.of("versionNo", versionNo, "locale", v.getLocale(), "author", v.getCreatedBy()));
        return toView(v);
    }

    @Transactional
    public VersionView reject(String templateId, int versionNo, String locale, String comment) {
        Template t = requireTemplate(templateId);
        TemplateVersion v = requireVersion(templateId, versionNo, locale);
        requireStatus(v, TemplateStatus.PENDING_APPROVAL, "reject");
        v.setStatus(TemplateStatus.DRAFT);
        v.setRejectionComment(comment);
        v.setUpdatedAt(clock.instant());
        versions.saveAndFlush(v);
        audit.record(t.getTenantId(), AuditAction.TEMPLATE_REJECTED, "TEMPLATE", t.getId(),
                Map.of("versionNo", versionNo, "locale", v.getLocale(), "comment", comment));
        return toView(v);
    }

    @Transactional
    public VersionView retire(String templateId, int versionNo, String locale) {
        Template t = requireTemplate(templateId);
        TemplateVersion v = requireVersion(templateId, versionNo, locale);
        requireStatus(v, TemplateStatus.PUBLISHED, "retire");
        v.setStatus(TemplateStatus.RETIRED);
        v.setUpdatedAt(clock.instant());
        versions.saveAndFlush(v);
        audit.record(t.getTenantId(), AuditAction.TEMPLATE_RETIRED, "TEMPLATE", t.getId(),
                Map.of("versionNo", versionNo, "locale", v.getLocale(), "reason", "retired"));
        return toView(v);
    }

    /** Validates and renders with sample data; never sends and never persists. */
    public PreviewResponse preview(PreviewRequest req) {
        Map<String, String> sample = req.sampleData() == null ? Map.of() : req.sampleData();
        try {
            Set<String> declared = req.variables() == null
                    ? new LinkedHashSet<>(sample.keySet()) : new LinkedHashSet<>(req.variables());
            if (req.variables() == null) {
                // auto-declare placeholders found in the content so a quick preview needs no declaration list
                declared.addAll(findPlaceholders(req.subject()));
                declared.addAll(findPlaceholders(req.body()));
            }
            variableValidator.validateDeclared(declared);
            variableValidator.validateSupplied(declared, sample);
            RenderedContent out = renderer.render(req.channel(), blankToNull(req.subject()), req.body(), declared, sample);
            return new PreviewResponse(true, out.subject(), out.body(), out.html(), out.sms(), List.of());
        } catch (TemplateRenderException e) {
            return new PreviewResponse(false, null, null, req.channel() == Channel.EMAIL, null, e.getDetails().isEmpty()
                    ? List.of(new ApiError.Detail(null, e.getMessage())) : e.getDetails());
        }
    }

    private Set<String> findPlaceholders(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        var m = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]{0,63})\\s*(?:\\|[^{}|]*)?}}").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    // ------------------------------------------------------------------ resolution for sending

    /**
     * Resolves the PUBLISHED version to send for a tenant: the requested version, else the latest published for the
     * locale (falling back from exact locale to language to "en"). Returns {@code null} when nothing is published.
     */
    @Transactional(readOnly = true)
    public ResolvedTemplate resolvePublished(String tenantId, String code, Channel channel, String locale,
            Integer requestedVersionNo) {
        Template t = templates.findByTenantIdAndCodeAndChannel(tenantId, code, channel).orElse(null);
        if (t == null) {
            return null;
        }
        for (String candidate : localeCandidates(locale)) {
            TemplateVersion v = requestedVersionNo != null
                    ? versions.findByTemplateIdAndVersionNoAndLocaleAndStatus(t.getId(), requestedVersionNo, candidate,
                            TemplateStatus.PUBLISHED).orElse(null)
                    : versions.findFirstByTemplateIdAndLocaleAndStatusOrderByVersionNoDesc(t.getId(), candidate,
                            TemplateStatus.PUBLISHED).orElse(null);
            if (v != null) {
                return new ResolvedTemplate(t, v, readVariables(v));
            }
        }
        return null;
    }

    /** Looks up one specific published version (used at dispatch time with the pinned version). */
    @Transactional(readOnly = true)
    public ResolvedTemplate resolvePinned(String tenantId, String code, Channel channel, int versionNo, String locale) {
        Template t = templates.findByTenantIdAndCodeAndChannel(tenantId, code, channel).orElse(null);
        if (t == null) {
            return null;
        }
        // a pinned version stays usable even if it was RETIRED after pinning (in-flight messages keep their content)
        return versions.findByTemplateIdAndVersionNoAndLocale(t.getId(), versionNo, locale)
                .filter(v -> v.getStatus() == TemplateStatus.PUBLISHED || v.getStatus() == TemplateStatus.RETIRED)
                .map(v -> new ResolvedTemplate(t, v, readVariables(v))).orElse(null);
    }

    static List<String> localeCandidates(String locale) {
        Set<String> out = new LinkedHashSet<>();
        if (locale != null && !locale.isBlank()) {
            String n = normalizeLocale(locale);
            out.add(n);
            int dash = n.indexOf('-');
            if (dash > 0) {
                out.add(n.substring(0, dash));
            }
        }
        out.add("en");
        return new ArrayList<>(out);
    }

    public Set<String> declaredVariables(TemplateVersion v) {
        return readVariables(v);
    }

    // ------------------------------------------------------------------ helpers

    private Template requireTemplate(String id) {
        Template t = templates.findById(id).orElseThrow(() -> new ApiException.NotFoundException("Template"));
        authz.assertTenantAccess(t.getTenantId(), "Template");
        return t;
    }

    private TemplateVersion requireVersion(String templateId, int versionNo, String locale) {
        String l = normalizeLocale(locale == null || locale.isBlank() ? "en" : locale);
        return versions.findByTemplateIdAndVersionNoAndLocale(templateId, versionNo, l)
                .orElseThrow(() -> new ApiException.NotFoundException("Template version"));
    }

    private static void requireStatus(TemplateVersion v, TemplateStatus expected, String action) {
        if (v.getStatus() != expected) {
            throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Cannot " + action + " a version in status " + v.getStatus() + " (expected " + expected + ")");
        }
    }

    private void validateContent(Channel channel, String subject, String body, Collection<String> declared) {
        variableValidator.validateDeclared(declared);
        renderer.validate(channel, blankToNull(subject), body, new LinkedHashSet<>(declared));
    }

    private TemplateVersion newVersion(Template t, int versionNo, String locale, String subject, String body,
            List<String> declared, Instant now) {
        TemplateVersion v = new TemplateVersion();
        v.setId(Ids.newId());
        v.setTemplateId(t.getId());
        v.setVersionNo(versionNo);
        v.setLocale(locale);
        v.setStatus(TemplateStatus.DRAFT);
        v.setSubject(blankToNull(subject));
        v.setBody(body);
        v.setVariablesJson(writeVariables(declared));
        v.setCreatedBy(authz.actor());
        v.setCreatedAt(now);
        v.setUpdatedAt(now);
        return v;
    }

    private void touch(Template t) {
        t.setUpdatedAt(clock.instant());
        templates.save(t);
    }

    private static List<String> normalizeVariables(Collection<String> in) {
        return in == null ? List.of() : in.stream().filter(s -> s != null).map(String::trim).distinct().toList();
    }

    private String writeVariables(List<String> names) {
        try {
            return mapper.writeValueAsString(names);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> readVariables(TemplateVersion v) {
        try {
            List<String> l = mapper.readValue(v.getVariablesJson(), new TypeReference<List<String>>() {});
            return new LinkedHashSet<>(l);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("Corrupt variables_json for template version " + v.getId());
        }
    }

    static String normalizeLocale(String locale) {
        String[] parts = locale.trim().replace('_', '-').split("-");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            sb.append('-').append(parts[i].length() == 2 ? parts[i].toUpperCase(Locale.ROOT) : parts[i]);
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private VersionView toView(TemplateVersion v) {
        return new VersionView(v.getId(), v.getVersionNo(), v.getLocale(), v.getStatus(), v.getSubject(), v.getBody(),
                new ArrayList<>(readVariables(v)), v.getCreatedBy(), v.getApprovedBy(), v.getPublishedAt(),
                v.getRejectionComment(), v.getCreatedAt(), v.getUpdatedAt(), v.getVersion());
    }

    private TemplateDetail toDetail(Template t, List<TemplateVersion> vs) {
        return new TemplateDetail(t.getId(), t.getTenantId(), t.getCode(), t.getName(), t.getChannel(), t.getCategory(),
                t.getCreatedAt(), t.getUpdatedAt(), vs.stream().map(this::toView).toList());
    }

    private TemplateSummary toSummary(Template t, List<TemplateVersion> vs) {
        Map<TemplateStatus, Long> counts = new EnumMap<>(TemplateStatus.class);
        vs.forEach(v -> counts.merge(v.getStatus(), 1L, Long::sum));
        List<String> published = vs.stream().filter(v -> v.getStatus() == TemplateStatus.PUBLISHED)
                .map(TemplateVersion::getLocale).distinct().sorted().toList();
        int latest = vs.stream().mapToInt(TemplateVersion::getVersionNo).max().orElse(0);
        return new TemplateSummary(t.getId(), t.getTenantId(), t.getCode(), t.getName(), t.getChannel(), t.getCategory(),
                latest, published, counts, t.getCreatedAt(), t.getUpdatedAt());
    }
}
