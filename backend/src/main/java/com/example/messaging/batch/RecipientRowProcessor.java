package com.example.messaging.batch;

import com.example.messaging.common.ApiException;
import com.example.messaging.common.Ids;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Priority;
import com.example.messaging.domain.Recipient;
import com.example.messaging.repository.RecipientRepository;
import com.example.messaging.service.TemplateService;
import com.example.messaging.template.TemplateRenderer;
import com.example.messaging.template.VariableValidator;
import com.example.messaging.web.dto.MessageDtos.SubmitMessageRequest;
import com.example.messaging.web.dto.MessageDtos.TemplateRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.batch.item.ItemProcessor;

/**
 * Validates one row and turns it into a submit request.
 *
 * <ol>
 *   <li>tokenising problems recorded by the reader, then value rules ({@link CsvRowValidator})</li>
 *   <li>duplicate detection: a small recent-refs set (adjacent duplicates, same chunk) and the durable
 *       {@code (job_id, external_ref)} ledger; a duplicate is recorded and <b>filtered</b> (returns null)</li>
 *   <li>recipient lookup scoped to the job's tenant</li>
 *   <li>template check (published, category matches, variables valid, dry render) with a per-run cache, so a row that
 *       can never be sent is rejected here instead of failing - and rolling back - a whole chunk in the writer</li>
 * </ol>
 * Bad rows throw {@link BulkRowException}, which the skip policy turns into a {@code bulk_job_failure} record.
 */
public class RecipientRowProcessor implements ItemProcessor<RecipientRow, BulkMessageItem> {

    private final BulkJobContext ctx;
    private final RecipientRepository recipients;
    private final TemplateService templates;
    private final VariableValidator variableValidator;
    private final TemplateRenderer renderer;
    private final BulkJobStore store;
    private final int maxVariableLength;
    private final RecentRefs recent;
    private final Map<String, Optional<TemplateService.ResolvedTemplate>> templateCache = new ConcurrentHashMap<>();

    public RecipientRowProcessor(BulkJobContext ctx, RecipientRepository recipients, TemplateService templates,
            VariableValidator variableValidator, TemplateRenderer renderer, BulkJobStore store,
            MessagingProperties props, int chunkSize) {
        this.ctx = ctx;
        this.recipients = recipients;
        this.templates = templates;
        this.variableValidator = variableValidator;
        this.renderer = renderer;
        this.store = store;
        this.maxVariableLength = props.templates().maxVariableLength();
        this.recent = new RecentRefs(Math.max(1000, chunkSize * 4));
    }

    @Override
    public BulkMessageItem process(RecipientRow row) {
        if (row.parseError() != null) {
            throw new BulkRowException(row.parseError());
        }
        String ref = CsvRowValidator.validateExternalRef(row.externalRef());
        String locale = CsvRowValidator.validateLocale(row.locale());
        Map<String, String> vars = new LinkedHashMap<>();
        row.variables().forEach((name, value) -> {
            String v = CsvRowValidator.validateValue(name, value, maxVariableLength);
            if (!v.isEmpty()) {
                vars.put(name, v);
            }
        });

        if (isDuplicate(row, ref)) {
            return null; // filtered, already recorded
        }

        Recipient recipient = recipients.findByTenantIdAndExternalRef(ctx.tenantId(), ref)
                .orElseThrow(() -> new BulkRowException("RECIPIENT_NOT_FOUND"));
        checkTemplate(locale != null ? locale : recipient.getLocale(), vars);

        Priority priority = ctx.category() == Category.MARKETING ? Priority.LOW : Priority.NORMAL;
        SubmitMessageRequest request = new SubmitMessageRequest(
                "bulk:" + ctx.jobId() + ":" + ref,
                recipient.getId(),
                null,
                ctx.channel(),
                ctx.category(),
                priority,
                new TemplateRef(ctx.templateCode(), locale, null),
                vars,
                null,
                List.of(),
                null,
                Map.of("bulkJobId", ctx.jobId()));
        return new BulkMessageItem(row, request, idempotencyKey(ctx.jobId(), ref));
    }

    /** {@code bulk-<jobId>-<externalRef>}: deterministic, so reprocessing a row can never create a second message. */
    public static String idempotencyKey(String jobId, String externalRef) {
        return "bulk-" + jobId + "-" + externalRef;
    }

    private boolean isDuplicate(RecipientRow row, String ref) {
        Long seen = recent.get(ref);
        if (seen == null) {
            BulkJobStore.ClaimResult c = store.claimRow(ctx.jobId(), ref, row.lineNo());
            if (c.claim() == BulkJobStore.Claim.DUPLICATE) {
                seen = c.firstLine();
            } else {
                recent.put(ref, row.lineNo());
                return false;
            }
        } else if (seen == row.lineNo()) {
            return false;
        }
        store.recordDuplicate(ctx.jobId(), row.lineNo(), "DUPLICATE_EXTERNAL_REF: first seen on line " + seen,
                Ids.sha256Hex(row.rawLine()));
        return true;
    }

    private void checkTemplate(String effectiveLocale, Map<String, String> vars) {
        Optional<TemplateService.ResolvedTemplate> resolved = templateCache.computeIfAbsent(
                effectiveLocale == null ? "" : effectiveLocale,
                l -> Optional.ofNullable(templates.resolvePublished(ctx.tenantId(), ctx.templateCode(), ctx.channel(),
                        l.isEmpty() ? null : l, null)));
        TemplateService.ResolvedTemplate t = resolved.orElseThrow(() -> new BulkRowException("TEMPLATE_NOT_FOUND",
                "no published '" + ctx.templateCode() + "' template for " + ctx.channel()));
        if (t.template().getCategory() != ctx.category()) {
            throw new BulkRowException("CATEGORY_MISMATCH", "template category is " + t.template().getCategory());
        }
        try {
            variableValidator.validateSupplied(t.declaredVariables(), vars);
            renderer.render(ctx.channel(), t.version().getSubject(), t.version().getBody(), t.declaredVariables(), vars);
        } catch (ApiException e) {
            throw new BulkRowException(e.getCode().name(), e.getMessage());
        }
    }

    /** Bounded, thread-safe recent externalRef to line map (least recently used entries are dropped). */
    static final class RecentRefs {
        private final Map<String, Long> map;

        RecentRefs(int capacity) {
            this.map = new java.util.LinkedHashMap<>(capacity, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > capacity;
                }
            };
        }

        synchronized Long get(String ref) {
            return map.get(ref);
        }

        synchronized void put(String ref, long line) {
            map.put(ref, line);
        }
    }
}
