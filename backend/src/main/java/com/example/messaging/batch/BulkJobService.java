package com.example.messaging.batch;

import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.common.Ids;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.BulkJob;
import com.example.messaging.domain.BulkJobFailure;
import com.example.messaging.domain.BulkJobStatus;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.repository.BulkJobFailureRepository;
import com.example.messaging.repository.BulkJobRepository;
import com.example.messaging.repository.Specs;
import com.example.messaging.security.AuthorizationService;
import com.example.messaging.security.CurrentPrincipal;
import com.example.messaging.security.Roles;
import com.example.messaging.security.TenantContext;
import com.example.messaging.service.AuditService;
import com.example.messaging.service.TemplateService;
import com.example.messaging.web.dto.BulkJobDtos.BulkJobFailureView;
import com.example.messaging.web.dto.BulkJobDtos.BulkJobView;
import com.example.messaging.web.dto.BulkJobDtos.GenerateSyntheticRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.UnexpectedJobExecutionException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk job use cases: create from an uploaded CSV or a synthetic dataset, list/get/failures, stop and restart.
 *
 * <p>Authorization (every mutating operation): the caller needs one of the roles SENDER, TENANT_ADMIN,
 * PLATFORM_ADMIN <b>and</b> the OAuth scope {@code messages:bulk}. A refusal is audited ({@value #DENIED}) and answered
 * 403 before anything is read or written. Reads need a normal authenticated role and are tenant scoped (another
 * tenant's job is a 404).
 */
@Service
public class BulkJobService {

    public static final String SCOPE_BULK = "messages:bulk";
    public static final String ENTITY = "BULK_JOB";
    public static final String DENIED = "BULK_JOB_DENIED";
    private static final Set<String> BULK_ROLES = Set.of(Roles.SENDER, Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN);
    private static final Pattern TEMPLATE_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Logger log = LoggerFactory.getLogger(BulkJobService.class);

    private final BulkJobRepository jobs;
    private final BulkJobFailureRepository failures;
    private final BulkJobStore store;
    private final BulkUploadStorage storage;
    private final SyntheticRecipientProvisioner provisioner;
    private final TenantContext tenantContext;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final TemplateService templates;
    private final JobLauncher launcher;
    private final JobOperator operator;
    private final JobExplorer explorer;
    private final BulkExecutionRecovery recovery;
    private final Job job;
    private final BulkProperties bulk;
    private final MessagingProperties props;
    private final MessagingMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BulkJobService(BulkJobRepository jobs, BulkJobFailureRepository failures, BulkJobStore store,
            BulkUploadStorage storage, SyntheticRecipientProvisioner provisioner, TenantContext tenantContext,
            AuthorizationService authz, AuditService audit, TemplateService templates,
            @Qualifier("bulkJobLauncher") JobLauncher launcher, @Qualifier("bulkJobOperator") JobOperator operator,
            JobExplorer explorer, BulkExecutionRecovery recovery, @Qualifier("bulkSendJob") Job job, BulkProperties bulk, MessagingProperties props,
            MessagingMetrics metrics, Clock clock, PlatformTransactionManager tm) {
        this.jobs = jobs;
        this.failures = failures;
        this.store = store;
        this.storage = storage;
        this.provisioner = provisioner;
        this.tenantContext = tenantContext;
        this.authz = authz;
        this.audit = audit;
        this.templates = templates;
        this.launcher = launcher;
        this.operator = operator;
        this.explorer = explorer;
        this.recovery = recovery;
        this.job = job;
        this.bulk = bulk;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    // ================================================================== create

    public BulkJobView create(String name, String templateCode, Channel channel, Category category,
            Integer ratePerSecond, MultipartFile file) {
        requireBulkSender("create", null);
        String tenantId = tenantContext.requireTenant();
        String jobName = validateName(name);
        validateTemplate(tenantId, templateCode, channel, category);
        int rate = validateRate(ratePerSecond);
        if (file == null || file.isEmpty()) {
            throw new ApiException.ValidationException("file", "a non-empty CSV file is required");
        }
        String jobId = Ids.newId();
        BulkUploadStorage.StoredUpload stored;
        try (InputStream in = file.getInputStream()) {
            stored = storage.store(tenantId, jobId, in, file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return createAndLaunch(jobId, tenantId, jobName, templateCode, channel, category, rate, stored.displayName(),
                stored.rows(), stored.path(), Map.of("source", "upload", "bytes", stored.bytes()));
    }

    /** Creates a synthetic dataset (temp CSV in the tenant's upload directory) and runs the normal job on it. */
    public BulkJobView generateSynthetic(GenerateSyntheticRequest req, boolean createRecipients) {
        requireBulkSender("generate-synthetic", null);
        String tenantId = tenantContext.requireTenant();
        long cap = Math.min(props.bulk().maxRows(), bulk.maxSyntheticRows());
        if (req.rows() == null || req.rows() > cap) {
            throw new ApiException.ValidationException("rows", "rows must be between 1 and " + cap);
        }
        double dup = req.duplicateRate() == null ? 0.02 : req.duplicateRate();
        double inv = req.invalidRate() == null ? 0.02 : req.invalidRate();
        if (dup + inv > 1.0) {
            throw new ApiException.ValidationException("duplicateRate", "duplicateRate + invalidRate must not exceed 1");
        }
        String code = req.templateCode() == null ? "TRADE_CONFIRMATION" : req.templateCode();
        Channel channel = req.channel() == null ? Channel.EMAIL : req.channel();
        Category category = req.category() == null ? Category.TRANSACTIONAL : req.category();
        TemplateService.ResolvedTemplate template = validateTemplate(tenantId, code, channel, category);
        // generate exactly the columns the template declares (the seeded templates differ per tenant)
        java.util.List<String> variables = template.declaredVariables().stream().sorted().toList();

        String jobId = Ids.newId();
        Path file;
        SyntheticDatasetGenerator.Result r;
        try {
            if (createRecipients) {
                provisioner.ensure(tenantId, req.rows());
            }
            file = storage.newJobFile(tenantId, jobId);
            r = SyntheticDatasetGenerator.generate(file,
                    SyntheticDatasetGenerator.Options.of(req.rows(), dup, inv).withVariables(variables));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "synthetic");
        detail.put("valid", r.valid());
        detail.put("duplicates", r.duplicates());
        detail.put("invalid", r.invalid());
        detail.put("createRecipients", createRecipients);
        return createAndLaunch(jobId, tenantId, "synthetic-" + req.rows() + "-rows", code, channel, category,
                bulk.defaultRatePerSecond(), "synthetic-" + jobId.substring(0, 8) + ".csv", r.rows(), file, detail);
    }

    private BulkJobView createAndLaunch(String jobId, String tenantId, String name, String templateCode,
            Channel channel, Category category, int rate, String fileName, long rows, Path file,
            Map<String, Object> auditExtra) {
        Instant now = clock.instant();
        BulkJob j = new BulkJob();
        j.setId(jobId);
        j.setTenantId(tenantId);
        j.setName(name);
        j.setStatus(BulkJobStatus.CREATED);
        j.setTemplateCode(templateCode);
        j.setChannel(channel);
        j.setCategory(category);
        j.setTotal(rows);
        j.setFileName(fileName);
        j.setRateLimitPerSec(rate > 0 ? rate : null);
        j.setCreatedBy(authz.actor());
        j.setCreatedAt(now);
        j.setUpdatedAt(now);
        try {
            tx.executeWithoutResult(s -> {
                jobs.save(j);
                Map<String, Object> detail = new LinkedHashMap<>(auditExtra);
                detail.put("name", name);
                detail.put("templateCode", templateCode);
                detail.put("channel", channel.name());
                detail.put("category", category.name());
                detail.put("total", rows);
                detail.put("ratePerSecond", rate);
                audit.record(tenantId, AuditAction.BULK_JOB_CREATED, ENTITY, jobId, detail);
            });
        } catch (RuntimeException e) {
            storage.deleteQuietly(file);
            throw e;
        }
        metrics.bulkProgress(jobId, 0.0);
        launch(jobId, tenantId, file);
        return view(jobs.findById(jobId).orElseThrow());
    }

    private void launch(String jobId, String tenantId, Path file) {
        JobParameters params = new JobParametersBuilder()
                .addString(BulkJobConfig.PARAM_JOB_ID, jobId)
                .addString(BulkJobConfig.PARAM_TENANT, tenantId)
                .addString(BulkJobConfig.PARAM_FILE, file.toString())
                .toJobParameters();
        JobExecution execution;
        try {
            execution = launcher.run(job, params);
        } catch (Exception e) {
            failLaunch(jobId, file, e.getClass().getSimpleName());
            throw new ApiException.ServiceUnavailableException("The bulk job could not be started", 30);
        }
        if (execution.getStatus() == BatchStatus.FAILED) {
            failLaunch(jobId, file, "executor saturated");
            throw new ApiException.ServiceUnavailableException(
                    "Too many bulk jobs are queued; retry later (job " + jobId + " was not started)", 30);
        }
        store.setExecutionId(jobId, execution.getId());
    }

    private void failLaunch(String jobId, Path file, String why) {
        log.warn("bulk job {} was not started: {}", jobId, why);
        store.markFinishedIfOpen(jobId, BulkJobStatus.FAILED);
        storage.deleteQuietly(file);
    }

    // ================================================================== queries

    private static final Set<String> SORTABLE = Set.of("createdAt", "startedAt", "finishedAt", "status", "name");
    private static final Set<String> SORTABLE_FAILURES = Set.of("lineNo", "createdAt");

    public Set<String> sortableJobs() {
        return SORTABLE;
    }

    public Set<String> sortableFailures() {
        return SORTABLE_FAILURES;
    }

    public Page<BulkJobView> list(Pageable pageable) {
        Specification<BulkJob> spec = Specification.<BulkJob>where(Specs.eq("tenantId", tenantContext.scopeTenant()));
        return jobs.findAll(spec, pageable).map(this::view);
    }

    public BulkJobView get(String id) {
        return view(requireJob(id));
    }

    public Page<BulkJobFailureView> failures(String id, Pageable pageable) {
        requireJob(id);
        return failures.findByJobId(id, pageable).map(BulkJobService::view);
    }

    private BulkJob requireJob(String id) {
        BulkJob j = jobs.findById(id).orElseThrow(() -> new ApiException.NotFoundException("Bulk job"));
        authz.assertTenantAccess(j.getTenantId(), "Bulk job");
        return j;
    }

    // ================================================================== stop / restart

    public BulkJobView stop(String id) {
        requireBulkSender("stop", id);
        BulkJob j = requireJob(id);
        if (j.getStatus() != BulkJobStatus.RUNNING || j.getSpringBatchExecutionId() == null) {
            throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Only a RUNNING bulk job can be stopped (status is " + j.getStatus() + ")");
        }
        long executionId = j.getSpringBatchExecutionId();
        if (!recovery.isLive(executionId)) {
            // RUNNING in the database but no worker of this JVM runs it: the process that did died. Close the orphaned
            // batch execution so the job can be restarted (runbook bulk-job-stuck.md).
            recovery.close(executionId, BatchStatus.STOPPED, "stopped by " + authz.actor() + ": no live worker");
            store.markFinishedIfOpen(id, BulkJobStatus.STOPPED);
            audit.record(j.getTenantId(), AuditAction.BULK_JOB_STOPPED, ENTITY, id,
                    Map.of("executionId", executionId, "orphaned", true));
            return view(jobs.findById(id).orElseThrow());
        }
        try {
            operator.stop(executionId);
        } catch (Exception e) {
            throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "The bulk job is not running in the batch engine");
        }
        audit.record(j.getTenantId(), AuditAction.BULK_JOB_STOPPED, ENTITY, id, Map.of("executionId", executionId));
        awaitEnd(executionId);
        return view(jobs.findById(id).orElseThrow());
    }

    public BulkJobView restart(String id) {
        requireBulkSender("restart", id);
        BulkJob j = requireJob(id);
        BulkJobStatus before = j.getStatus();
        if ((before != BulkJobStatus.FAILED && before != BulkJobStatus.STOPPED)
                || j.getSpringBatchExecutionId() == null) {
            throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "Only a FAILED or STOPPED bulk job can be restarted (status is " + before + ")");
        }
        Path file = storage.requireInsideUploadDir(storage.baseDir().resolve(j.getTenantId()).resolve(id + ".csv"));
        if (!Files.isRegularFile(file)) {
            throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "The uploaded file of this job is no longer available");
        }
        if (!store.casStatus(id, before, BulkJobStatus.CREATED)) {
            throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "The bulk job changed state concurrently");
        }
        Long newExecution = null;
        try {
            newExecution = restartWithRetry(j.getSpringBatchExecutionId());
        } catch (Exception e) {
            store.casStatus(id, BulkJobStatus.CREATED, before);
            throw new ApiException.ConflictException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "The bulk job cannot be restarted: " + e.getClass().getSimpleName());
        }
        JobExecution started = explorer.getJobExecution(newExecution);
        if (started != null && started.getStatus() == BatchStatus.FAILED && started.getEndTime() != null
                && started.getStartTime() == null) {
            store.casStatus(id, BulkJobStatus.CREATED, before);
            throw new ApiException.ServiceUnavailableException("Too many bulk jobs are queued; retry later", 30);
        }
        store.setExecutionId(id, newExecution);
        audit.record(j.getTenantId(), AuditAction.BULK_JOB_RESTARTED, ENTITY, id,
                Map.of("fromStatus", before.name(), "executionId", newExecution));
        return view(jobs.findById(id).orElseThrow());
    }

    /**
     * The listener marks the job FAILED/STOPPED slightly before the batch repository persists the execution's final
     * status; a restart in that window sees "already running". Retry briefly instead of failing the request.
     */
    private Long restartWithRetry(long executionId) throws Exception {
        for (int attempt = 0;; attempt++) {
            try {
                return operator.restart(executionId);
            } catch (UnexpectedJobExecutionException e) {
                if (!(e.getCause() instanceof JobExecutionAlreadyRunningException) || attempt >= 40) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    private void awaitEnd(long executionId) {
        long deadline = System.nanoTime() + bulk.stopWait().toNanos();
        while (System.nanoTime() < deadline) {
            JobExecution e = explorer.getJobExecution(executionId);
            if (e == null || !e.isRunning()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ================================================================== authorization and validation

    /**
     * Role (SENDER, TENANT_ADMIN, PLATFORM_ADMIN) and scope {@code messages:bulk}. Audited refusal; 403.
     *
     * @param jobId audit entity id, {@code null} for create
     */
    void requireBulkSender(String action, String jobId) {
        CurrentPrincipal p = tenantContext.requirePrincipal();
        boolean role = p.roles().stream().anyMatch(BULK_ROLES::contains);
        boolean scope = p.hasScope(SCOPE_BULK);
        if (role && scope) {
            return;
        }
        String tenant = null;
        try {
            tenant = tenantContext.scopeTenant();
        } catch (RuntimeException ignored) {
            // an invalid X-Tenant-Id must not hide the refusal
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", action);
        detail.put("missingRole", !role);
        detail.put("missingScope", !scope);
        audit.recordAs(p.subject(), authz.actorRoles(), tenant, DENIED, ENTITY, jobId == null ? "n/a" : jobId, detail);
        throw new ApiException.ForbiddenException(
                "Bulk operations require role SENDER, TENANT_ADMIN or PLATFORM_ADMIN and scope " + SCOPE_BULK);
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new ApiException.ValidationException("name", "name is required (1-200 characters)");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new ApiException.ValidationException("name", "name must not contain control characters");
            }
        }
        return name.trim();
    }

    private int validateRate(Integer ratePerSecond) {
        if (ratePerSecond == null) {
            return bulk.defaultRatePerSecond();
        }
        if (ratePerSecond < 1 || ratePerSecond > bulk.maxRatePerSecond()) {
            throw new ApiException.ValidationException("ratePerSecond",
                    "ratePerSecond must be between 1 and " + bulk.maxRatePerSecond());
        }
        return ratePerSecond;
    }

    private TemplateService.ResolvedTemplate validateTemplate(String tenantId, String code, Channel channel, Category category) {
        if (code == null || !TEMPLATE_CODE.matcher(code).matches()) {
            throw new ApiException.ValidationException("templateCode", "templateCode is required (letters, digits, . _ -)");
        }
        if (channel == null) {
            throw new ApiException.ValidationException("channel", "channel is required");
        }
        if (category == null) {
            throw new ApiException.ValidationException("category", "category is required");
        }
        TemplateService.ResolvedTemplate t = templates.resolvePublished(tenantId, code, channel, null, null);
        if (t == null) {
            throw new ApiException.UnprocessableException(ErrorCode.TEMPLATE_NOT_FOUND,
                    "No PUBLISHED template '" + code + "' for channel " + channel);
        }
        if (t.template().getCategory() != category) {
            throw new ApiException.ValidationException("category",
                    "category must match the template category " + t.template().getCategory());
        }
        return t;
    }

    // ================================================================== mapping

    private BulkJobView view(BulkJob j) {
        return new BulkJobView(j.getId(), j.getTenantId(), j.getName(), j.getStatus(), j.getTotal(), j.getProcessed(),
                j.getSucceeded(), j.getFailed(), j.getDuplicate(), j.getFileName(), j.getRateLimitPerSec(),
                j.getCreatedBy(), j.getStartedAt(), j.getFinishedAt());
    }

    private static BulkJobFailureView view(BulkJobFailure f) {
        return new BulkJobFailureView(f.getLineNo(), f.getReason(), f.getRawLineHash());
    }
}
