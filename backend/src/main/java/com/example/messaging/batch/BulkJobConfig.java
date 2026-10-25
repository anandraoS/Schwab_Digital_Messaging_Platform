package com.example.messaging.batch;

import com.example.messaging.common.ApiException;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.repository.RecipientRepository;
import com.example.messaging.service.NotificationService;
import com.example.messaging.service.TemplateService;
import com.example.messaging.template.TemplateRenderer;
import com.example.messaging.template.TemplateRenderException;
import com.example.messaging.template.VariableValidator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.configuration.support.ReferenceJobFactory;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.SimpleJobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.FaultTolerantStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch 5 definition of the bulk send: job {@value #JOB_NAME}, one chunk-oriented step
 * {@value #STEP_NAME}.
 *
 * <pre>
 * FlatFileItemReader (streamed CSV) -> RecipientRowProcessor (validate, dedupe, resolve) -> BulkMessageWriter
 * </pre>
 *
 * <ul>
 *   <li>chunk size {@code messaging.bulk.chunk-size}; one transaction per chunk (messages, outbox, audit, counters)</li>
 *   <li>fault tolerant: up to {@code messaging.bulk.skip-limit} bad rows are skipped and recorded; one more fails the
 *       job; transient database errors are retried {@code retry-limit} times with exponential back-off</li>
 *   <li>{@code messaging.bulk.concurrency} worker threads (bounded {@link ThreadPoolTaskExecutor}) behind a
 *       {@link SynchronizedItemStreamReader}. With more than one worker the reader cannot checkpoint an exact
 *       position, so {@code saveState} is off and a restart re-reads the file from the top: already processed rows
 *       are recognised by the ledger and the idempotency key and produce no second message. With one worker the
 *       reader checkpoints per chunk and a restart resumes from the last committed chunk.</li>
 *   <li>JDBC job repository ({@code V100__spring_batch.sql}); {@code spring.batch.job.enabled=false}: jobs are only
 *       launched through {@link BulkJobService}</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(BulkProperties.class)
public class BulkJobConfig {

    public static final String JOB_NAME = "bulkSendJob";
    public static final String STEP_NAME = "readValidateStep";
    public static final String PARAM_JOB_ID = "bulkJobId";
    public static final String PARAM_FILE = "filePath";
    public static final String PARAM_TENANT = "tenantId";

    // ------------------------------------------------------------------ executors, launcher, operator

    /** Chunk worker threads: the hard bound on parallel chunk writers. */
    @Bean(name = "bulkStepExecutor")
    public ThreadPoolTaskExecutor bulkStepExecutor(MessagingProperties props) {
        int n = Math.max(1, props.bulk().concurrency());
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("bulk-chunk-");
        ex.setCorePoolSize(n);
        ex.setMaxPoolSize(n);
        ex.setQueueCapacity(1000);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        return ex;
    }

    /** Threads that run whole jobs; a full queue makes job creation answer 503 instead of piling up work. */
    @Bean(name = "bulkLaunchExecutor")
    public ThreadPoolTaskExecutor bulkLaunchExecutor(BulkProperties props) {
        int n = Math.max(1, props.maxParallelJobs());
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("bulk-job-");
        ex.setCorePoolSize(n);
        ex.setMaxPoolSize(n);
        ex.setQueueCapacity(Math.max(1, props.launchQueueCapacity()));
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        return ex;
    }

    @Bean(name = "bulkJobLauncher")
    public TaskExecutorJobLauncher bulkJobLauncher(JobRepository repository,
            @Qualifier("bulkLaunchExecutor") TaskExecutor executor) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
        return launcher;
    }

    @Bean(name = "bulkJobRegistry")
    public MapJobRegistry bulkJobRegistry() {
        return new MapJobRegistry();
    }

    @Bean(name = "bulkJobOperator")
    public SimpleJobOperator bulkJobOperator(JobRepository repository, JobExplorer explorer,
            @Qualifier("bulkJobRegistry") JobRegistry registry, @Qualifier("bulkJobLauncher") JobLauncher launcher)
            throws Exception {
        SimpleJobOperator op = new SimpleJobOperator();
        op.setJobRepository(repository);
        op.setJobExplorer(explorer);
        op.setJobRegistry(registry);
        op.setJobLauncher(launcher);
        op.afterPropertiesSet();
        return op;
    }

    /** Makes the job known to the operator (needed for restart by execution id). */
    @Bean
    public SmartInitializingSingleton bulkJobRegistration(@Qualifier("bulkJobRegistry") MapJobRegistry registry,
            Job bulkSendJob) {
        return () -> {
            try {
                registry.register(new ReferenceJobFactory(bulkSendJob));
            } catch (org.springframework.batch.core.configuration.DuplicateJobException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    // ------------------------------------------------------------------ job and step

    @Bean
    public BulkJobListener bulkJobListener(BulkJobStore store, JdbcTemplate jdbc, Clock clock,
            MessagingProperties props, BulkExecutionRecovery recovery, BulkUploadStorage storage, BulkProperties bulk) {
        return new BulkJobListener(store, jdbc, clock, props.bulk().skipLimit(), recovery, storage,
                bulk.deleteFileOnCompletion());
    }

    @Bean(name = "bulkSendJob")
    public Job bulkSendJob(JobRepository repository, @Qualifier("readValidateStep") Step readValidateStep,
            BulkJobListener listener) {
        return new JobBuilder(JOB_NAME, repository).start(readValidateStep)
                .listener((JobExecutionListener) listener).build();
    }

    @Bean(name = "readValidateStep")
    @SuppressWarnings({"deprecation", "unchecked"})
    public Step readValidateStep(JobRepository repository, PlatformTransactionManager tm, MessagingProperties props,
            BulkProperties bulk, SynchronizedItemStreamReader<RecipientRow> bulkCsvReader,
            RecipientRowProcessor bulkRowProcessor, BulkMessageWriter bulkMessageWriter,
            BulkSkipListener bulkSkipListener, @Qualifier("bulkStepExecutor") ThreadPoolTaskExecutor stepExecutor) {
        int concurrency = Math.max(1, props.bulk().concurrency());
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(50);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(1000);

        FaultTolerantStepBuilder<RecipientRow, BulkMessageItem> builder = new StepBuilder(STEP_NAME, repository)
                .<RecipientRow, BulkMessageItem>chunk(Math.max(1, props.bulk().chunkSize()), tm)
                .reader(bulkCsvReader)
                .processor(bulkRowProcessor)
                .writer(bulkMessageWriter)
                .faultTolerant()
                .skipLimit(Math.max(0, props.bulk().skipLimit()))
                .skip(BulkRowException.class)
                .skip(FlatFileParseException.class)
                .skip(ApiException.ValidationException.class)
                .skip(ApiException.UnprocessableException.class)
                .skip(ApiException.NotFoundException.class)
                .skip(TemplateRenderException.class)
                .retryLimit(Math.max(1, bulk.retryLimit()))
                .retry(TransientDataAccessException.class)
                .retry(CannotCreateTransactionException.class)
                .backOffPolicy(backOff)
                .listener((SkipListener<RecipientRow, BulkMessageItem>) bulkSkipListener);
        if (concurrency > 1) {
            builder.taskExecutor((TaskExecutor) stepExecutor).throttleLimit(concurrency);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ step scoped collaborators

    @Bean
    @StepScope
    public SynchronizedItemStreamReader<RecipientRow> bulkCsvReader(
            @Value("#{jobParameters['" + PARAM_FILE + "']}") String filePath, BulkProperties bulk,
            MessagingProperties props, BulkUploadStorage storage) {
        Path file = storage.requireInsideUploadDir(Path.of(filePath));
        boolean saveState = props.bulk().concurrency() <= 1;
        FlatFileItemReader<RecipientRow> delegate = RecipientCsvReaderFactory.create(file, bulk.maxLineLength(),
                bulk.maxColumns(), saveState);
        SynchronizedItemStreamReader<RecipientRow> reader = new SynchronizedItemStreamReader<>();
        reader.setDelegate(delegate);
        return reader;
    }

    @Bean
    @StepScope
    public RecipientRowProcessor bulkRowProcessor(@Value("#{jobParameters['" + PARAM_JOB_ID + "']}") String jobId,
            @Value("#{jobParameters['" + PARAM_FILE + "']}") String filePath, BulkJobContextLoader loader,
            RecipientRepository recipients, TemplateService templates, VariableValidator validator,
            TemplateRenderer renderer, BulkJobStore store, MessagingProperties props) {
        return new RecipientRowProcessor(loader.load(jobId, filePath), recipients, templates, validator, renderer, store,
                props, props.bulk().chunkSize());
    }

    @Bean
    @StepScope
    public BulkMessageWriter bulkMessageWriter(@Value("#{jobParameters['" + PARAM_JOB_ID + "']}") String jobId,
            @Value("#{jobParameters['" + PARAM_FILE + "']}") String filePath, BulkJobContextLoader loader,
            NotificationService notifications, BulkJobStore store, BulkWriterStats stats) {
        BulkJobContext ctx = loader.load(jobId, filePath);
        return new BulkMessageWriter(ctx, notifications, store, TokenBucketRateLimiter.perSecond(ctx.ratePerSecond()),
                stats);
    }

    @Bean
    @StepScope
    public BulkSkipListener bulkSkipListener(@Value("#{jobParameters['" + PARAM_JOB_ID + "']}") String jobId,
            BulkJobStore store) {
        return new BulkSkipListener(jobId, store);
    }
}
