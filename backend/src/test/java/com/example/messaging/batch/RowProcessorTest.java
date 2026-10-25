package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.messaging.TestProps;
import com.example.messaging.common.Ids;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.example.messaging.domain.Recipient;
import com.example.messaging.domain.Template;
import com.example.messaging.domain.TemplateStatus;
import com.example.messaging.domain.TemplateVersion;
import com.example.messaging.repository.RecipientRepository;
import com.example.messaging.service.TemplateService;
import com.example.messaging.template.TemplateRenderer;
import com.example.messaging.template.VariableValidator;
import com.example.messaging.web.dto.MessageDtos.SubmitMessageRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Row validation, duplicate detection, recipient lookup and template checks of {@link RecipientRowProcessor}. */
class RowProcessorTest {

    static final String JOB = "11111111-2222-3333-4444-555555555555";
    static final String TENANT = "tenant-retail";

    RecipientRepository recipients;
    TemplateService templates;
    BulkJobStore store;
    BulkJobContext ctx;
    RecipientRowProcessor processor;

    @BeforeEach
    void setUp() {
        recipients = mock(RecipientRepository.class);
        templates = mock(TemplateService.class);
        store = mock(BulkJobStore.class);
        when(store.claimRow(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> new BulkJobStore.ClaimResult(BulkJobStore.Claim.NEW, inv.getArgument(2)));
        when(recipients.findByTenantIdAndExternalRef(eq(TENANT), anyString())).thenAnswer(inv -> {
            String ref = inv.getArgument(1);
            return ref.startsWith("CUST-") ? Optional.of(recipient("rcp-" + ref, "en")) : Optional.empty();
        });
        when(templates.resolvePublished(eq(TENANT), eq("TRADE_CONFIRMATION"), eq(Channel.EMAIL), any(), isNull()))
                .thenReturn(resolved(Category.TRANSACTIONAL, "Hello {{firstName|there}}, {{quantity}} {{symbol}}",
                        "{{firstName}}", "firstName", "quantity", "symbol"));
        ctx = new BulkJobContext(JOB, TENANT, "job", "TRADE_CONFIRMATION", Channel.EMAIL, Category.TRANSACTIONAL, 0,
                "svc", "/tmp/x.csv");
        processor = newProcessor(ctx);
    }

    RecipientRowProcessor newProcessor(BulkJobContext c) {
        var props = TestProps.defaults();
        return new RecipientRowProcessor(c, recipients, templates, new VariableValidator(props),
                new TemplateRenderer(props), store, props, 500);
    }

    static Recipient recipient(String id, String locale) {
        Recipient r = new Recipient();
        r.setId(id);
        r.setTenantId(TENANT);
        r.setLocale(locale);
        return r;
    }

    static TemplateService.ResolvedTemplate resolved(Category category, String body, String subject, String... vars) {
        Template t = new Template();
        t.setId("tpl");
        t.setTenantId(TENANT);
        t.setCode("TRADE_CONFIRMATION");
        t.setChannel(Channel.EMAIL);
        t.setCategory(category);
        TemplateVersion v = new TemplateVersion();
        v.setVersionNo(1);
        v.setLocale("en");
        v.setStatus(TemplateStatus.PUBLISHED);
        v.setSubject(subject);
        v.setBody(body);
        return new TemplateService.ResolvedTemplate(t, v, Set.of(vars));
    }

    static RecipientRow row(long line, String ref, String locale, String... kv) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            vars.put(kv[i], kv[i + 1]);
        }
        return new RecipientRow(line, ref, locale, vars, "raw-line-" + line, null);
    }

    static RecipientRow good(long line, String ref) {
        return row(line, ref, "en", "firstName", "Pat", "quantity", "10", "symbol", "ACME");
    }

    private void assertRejected(RecipientRow row, String code) {
        assertThatThrownBy(() -> processor.process(row)).isInstanceOf(BulkRowException.class)
                .satisfies(e -> assertThat(((BulkRowException) e).code()).isEqualTo(code));
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void validRow_becomesADeterministicSubmitRequest() {
        BulkMessageItem item = processor.process(good(7, "CUST-001"));

        assertThat(item.idempotencyKey()).isEqualTo("bulk-" + JOB + "-CUST-001");
        SubmitMessageRequest r = item.request();
        assertThat(r.recipientId()).isEqualTo("rcp-CUST-001");
        assertThat(r.recipient()).isNull();
        assertThat(r.channel()).isEqualTo(Channel.EMAIL);
        assertThat(r.category()).isEqualTo(Category.TRANSACTIONAL);
        assertThat(r.priority()).isEqualTo(Priority.NORMAL);
        assertThat(r.template().code()).isEqualTo("TRADE_CONFIRMATION");
        assertThat(r.template().locale()).isEqualTo("en");
        assertThat(r.template().version()).as("version is pinned at submit time").isNull();
        assertThat(r.variables()).containsEntry("firstName", "Pat").containsEntry("quantity", "10");
        assertThat(r.clientReference()).isEqualTo("bulk:" + JOB + ":CUST-001");
        assertThat(r.metadata()).containsEntry("bulkJobId", JOB);
        assertThat(item.lineNo()).isEqualTo(7);
        // the same row twice gives the same request (hash) and key: replays are no-ops
        assertThat(processor.process(good(7, "CUST-001")).request()).isEqualTo(r);
        verify(store, times(1)).claimRow(JOB, "CUST-001", 7); // second time served by the recent-refs set
    }

    @Test
    void idempotencyKey_neverExceedsTheServiceLimit() {
        String ref = "A".repeat(CsvRowValidator.MAX_EXTERNAL_REF);
        assertThat(RecipientRowProcessor.idempotencyKey(JOB, ref).length()).isLessThanOrEqualTo(128);
        assertRejected(row(2, "A".repeat(CsvRowValidator.MAX_EXTERNAL_REF + 1), "en"), "INVALID_EXTERNAL_REF");
    }

    @Test
    void marketingJobs_useLowPriority() {
        BulkJobContext marketing = new BulkJobContext(JOB, TENANT, "promo", "TRADE_CONFIRMATION", Channel.EMAIL,
                Category.MARKETING, 0, "svc", "/tmp/x.csv");
        when(templates.resolvePublished(eq(TENANT), eq("TRADE_CONFIRMATION"), eq(Channel.EMAIL), any(), isNull()))
                .thenReturn(resolved(Category.MARKETING, "{{firstName}}", "s", "firstName", "quantity", "symbol"));
        assertThat(newProcessor(marketing).process(good(2, "CUST-001")).request().priority()).isEqualTo(Priority.LOW);
    }

    @Test
    void emptyLocale_fallsBackToTheRecipientsLocale_forTheTemplateLookup() {
        processor.process(row(2, "CUST-002", "", "firstName", "Pat", "quantity", "1", "symbol", "A"));
        verify(templates).resolvePublished(TENANT, "TRADE_CONFIRMATION", Channel.EMAIL, "en", null);
        BulkMessageItem item = processor.process(row(3, "CUST-003", " es_MX ", "firstName", "Pat", "quantity", "1",
                "symbol", "A"));
        assertThat(item.request().template().locale()).isEqualTo("es-MX");
    }

    // ------------------------------------------------------------------ row validation

    @Test
    void parseErrorsFromTheReader_arePassedOn() {
        assertRejected(RecipientRow.broken(9, "x", "WRONG_COLUMN_COUNT: expected 8, found 3"), "WRONG_COLUMN_COUNT: expected 8, found 3");
    }

    @Test
    void badReferenceAndLocale() {
        assertRejected(good(2, ""), "MISSING_EXTERNAL_REF");
        assertRejected(good(2, "   "), "MISSING_EXTERNAL_REF");
        assertRejected(good(2, "-CUST-1"), "INVALID_EXTERNAL_REF");
        assertRejected(good(2, "=CUST"), "INVALID_EXTERNAL_REF");
        assertRejected(good(2, "CUST 1"), "INVALID_EXTERNAL_REF");
        assertRejected(good(2, "CUST/1"), "INVALID_EXTERNAL_REF");
        assertRejected(row(2, "CUST-001", "english"), "INVALID_LOCALE");
        assertRejected(row(2, "CUST-001", "x"), "INVALID_LOCALE");
    }

    @Test
    void cellValues_formulaInjectionControlCharactersAndLength() {
        assertRejected(row(2, "CUST-001", "en", "firstName", "=cmd|' /C calc'!A0"), "FORMULA_INJECTION");
        assertRejected(row(2, "CUST-001", "en", "firstName", "@SUM(1+1)"), "FORMULA_INJECTION");
        assertRejected(row(2, "CUST-001", "en", "firstName", "+cmd"), "FORMULA_INJECTION");
        assertRejected(row(2, "CUST-001", "en", "firstName", "-2+3"), "FORMULA_INJECTION");
        assertRejected(row(2, "CUST-001", "en", "firstName", "\t=1+1"), "FORMULA_INJECTION"); // tab is trimmed first
        assertRejected(row(2, "CUST-001", "en", "firstName", "a\u0007b"), "CONTROL_CHARACTER");
        assertRejected(row(2, "CUST-001", "en", "firstName", "a\u0000b"), "CONTROL_CHARACTER");
        assertRejected(row(2, "CUST-001", "en", "firstName", "x".repeat(2001)), "FIELD_TOO_LONG");
        // plain numbers, including signed ones, are not formulas
        assertThat(processor.process(row(2, "CUST-001", "en", "firstName", "Pat", "quantity", "-12.50", "symbol", "A")))
                .isNotNull();
        assertThat(processor.process(row(3, "CUST-002", "en", "firstName", "Pat", "quantity", "+5", "symbol", "A")))
                .isNotNull();
        assertThat(processor.process(row(4, "CUST-003", "en", "firstName", "Zoë Müller", "quantity", "5", "symbol", "A")))
                .as("non-ASCII text is fine").isNotNull();
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    void duplicateInTheLedger_isFilteredAndRecorded() {
        when(store.claimRow(JOB, "CUST-001", 9)).thenReturn(new BulkJobStore.ClaimResult(BulkJobStore.Claim.DUPLICATE, 3));

        assertThat(processor.process(good(9, "CUST-001"))).isNull();

        verify(store).recordDuplicate(JOB, 9, "DUPLICATE_EXTERNAL_REF: first seen on line 3", Ids.sha256Hex("raw-line-9"));
        verify(recipients, never()).findByTenantIdAndExternalRef(anyString(), anyString());
    }

    @Test
    void duplicateInTheSameChunk_isCaughtWithoutADatabaseRoundTrip() {
        assertThat(processor.process(good(2, "CUST-001"))).isNotNull();
        assertThat(processor.process(good(3, "CUST-002"))).isNotNull();
        assertThat(processor.process(good(4, "CUST-001"))).isNull();

        verify(store, times(1)).claimRow(JOB, "CUST-001", 2);
        verify(store, never()).claimRow(JOB, "CUST-001", 4);
        verify(store).recordDuplicate(eq(JOB), eq(4L), eq("DUPLICATE_EXTERNAL_REF: first seen on line 2"), anyString());
    }

    @Test
    void sameLineSeenAgain_isAReplayNotADuplicate() {
        when(store.claimRow(JOB, "CUST-001", 2)).thenReturn(new BulkJobStore.ClaimResult(BulkJobStore.Claim.SAME_LINE, 2));
        assertThat(processor.process(good(2, "CUST-001"))).isNotNull();
        verify(store, never()).recordDuplicate(anyString(), anyLong(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ recipient and template

    @Test
    void unknownRecipient_isRejectedPerTenant() {
        assertRejected(good(2, "SYN-0000001"), "RECIPIENT_NOT_FOUND");
        // the lookup is always scoped to the job's tenant
        verify(recipients).findByTenantIdAndExternalRef(TENANT, "SYN-0000001");
        verify(recipients, never()).findById(anyString());
    }

    @Test
    void templateProblems_areFoundBeforeTheWriter() {
        // variable the template does not declare
        assertRejected(row(2, "CUST-001", "en", "firstName", "Pat", "quantity", "1", "symbol", "A", "bogus", "x"),
                "TEMPLATE_RENDER_ERROR");
        // required variable (no default) missing
        assertRejected(row(3, "CUST-002", "en", "firstName", "Pat", "symbol", "A"), "TEMPLATE_VARIABLE_MISSING");
        // blank cells count as missing, not as empty text
        assertRejected(row(4, "CUST-003", "en", "firstName", "Pat", "quantity", "  ", "symbol", "A"),
                "TEMPLATE_VARIABLE_MISSING");
    }

    @Test
    void noPublishedTemplate_andCategoryMismatch() {
        when(templates.resolvePublished(eq(TENANT), eq("TRADE_CONFIRMATION"), eq(Channel.EMAIL), any(), isNull()))
                .thenReturn(null);
        assertRejected(good(2, "CUST-001"), "TEMPLATE_NOT_FOUND");

        processor = newProcessor(ctx); // the first processor remembers that there is no template
        when(templates.resolvePublished(eq(TENANT), eq("TRADE_CONFIRMATION"), eq(Channel.EMAIL), any(), isNull()))
                .thenReturn(resolved(Category.SERVICE, "{{quantity}}", "s", "firstName", "quantity", "symbol"));
        assertRejected(good(3, "CUST-002"), "CATEGORY_MISMATCH");
    }

    @Test
    void templateIsResolvedOncePerLocale_notOncePerRow() {
        for (int i = 0; i < 20; i++) {
            processor.process(good(2 + i, "CUST-" + String.format("%03d", i)));
        }
        verify(templates, times(1)).resolvePublished(TENANT, "TRADE_CONFIRMATION", Channel.EMAIL, "en", null);
    }

    @Test
    void toStringOfRowsAndItems_doesNotLeakContent() {
        RecipientRow r = good(5, "CUST-001");
        assertThat(r.toString()).isEqualTo("RecipientRow[line=5]").doesNotContain("Pat").doesNotContain("CUST");
        BulkMessageItem item = processor.process(r);
        assertThat(item.toString()).doesNotContain("Pat").doesNotContain("CUST").doesNotContain("rcp-");
    }
}
