# Appendix A. Traceability

The repository's traceability matrix is `docs/traceability/job-description-traceability-matrix.md` (with the machine-readable copy `job-description-traceability-matrix.csv`): for each stated job requirement it lists the implemented feature, the source location, the test or validation evidence, an interview talking point and the remaining limitation. See `docs/validation/` for which checks were actually executed and their results. This appendix is the chapter-level map inside this PDF: for each chapter, the primary code and the tests that prove it.

| Chapter | Primary code | Proving tests |
|---|---|---|
| 5 Submission | `web/MessageController`, `service/NotificationService`, `service/IdempotencyService` | `NotificationApiIT`, `IdempotencyServiceTest`, `BackpressureIT`, `OpenApiContractIT`, `FailureRecoveryIT#scenario02` |
| 6 Lifecycle | `domain/MessageStateMachine`, `domain/MessageStatus` | `MessageStateMachineTest`, `CancelRaceIT`, `ConcurrencyIT`, `MessageLifecycleIT` |
| 7 Dispatch | `scheduler/OutboxPublisher`, `messaging/DispatchConsumer`, `messaging/InMemoryMessageBus`, `messaging/RabbitMessageBus` | `DispatchFlowIT`, `IdempotentConsumerIT`, `InMemoryMessageBusTest`, `GracefulShutdownTest`, `RabbitTopologyTest` |
| 8 Providers | `provider/ChannelProvider`, `ProviderRegistry`, `ResilientProviderInvoker`, `provider/simulator/*` | `ProviderRegistryTest`, `ResilientProviderInvokerTest`, `SimulatedProviderTest`, `FaultInjectorTest`, `NewProviderWithoutOrchestrationChangeTest`, `ProviderOutcomeIT` |
| 9 Channels | `template/TemplateRenderer`, `callback/*PayloadMapper` | `TemplateRendererTest`, `ChannelDispatchIT`, `EventNormalizerTest` |
| 10 Templates | `service/TemplateService`, `web/TemplateController` | `TemplateRendererTest`, `TemplateLifecycleIT` |
| 11 Preferences | `service/PolicyService`, `service/PreferenceService`, `service/SystemSuppressionService` | `PolicyServiceTest`, `PreferenceApiIT`, `TenantIsolationIT` |
| 12 Callbacks, reconciliation | `callback/*`, `scheduler/StuckMessageSweeper`, `service/ReconciliationService` | `SignatureVerifierTest`, `ReplayGuardTest`, `EventNormalizerTest`, `CallbackApiIT`, `ReconciliationIT`, `StuckMessageSweeperTest` |
| 13 Retry, DLQ | `service/RetryPolicy`, `service/ReplayService`, `service/DefaultDeadLetterOperations` | `RetryPolicyTest`, `ReplayServiceIT`, `FailureRecoveryIT#scenario01`, `#scenario08` |
| 14 Bulk | `batch/*`, `web/BulkJobController` | `BulkJobBatchIT`, `BulkJobApiIT`, `BulkJobRestartConcurrentIT`, `FailureRecoveryBulkIT`, `CsvRulesTest`, `RowProcessorTest` |
| 15 Database | `backend/src/main/resources/db/migration/*`, `repository/*`, `scheduler/RetentionJob` | `RepositoryQueryCountIT`, `RetentionJobTest`, `OpenApiContractIT` |
| 16 Security, audit | `config/SecurityConfig`, `security/*`, `common/Redaction`, `service/AuditService` | `SecurityIT`, `SecurityMatrixTest`, `TenantIsolationIT`, `JwtAuthConverterTest`, `DevTokenDisabledIT`, `LoggingPrivacyIT`, `RedactionTest`, `RateLimitIT` |
| 17 to 19 Deployment, CI/CD | `cloud-foundry/*`, `infrastructure/*`, `.github/workflows/*`, `Jenkinsfile`, `bamboo-specs/*` | `scripts/validate-infra.sh`, `cloud-foundry/validate-manifest.mjs` (static checks only; deployments NOT executed) |
| 20 Observability, performance | `metrics/MessagingMetrics`, `observability/*`, `performance/*` | `scripts/lib/static-checks.mjs` (static); k6 NOT executed |
| 22 Failure scenarios | `pipeline/FailureRecoveryIT`, `batch/FailureRecoveryBulkIT`, `tests/failure-recovery/*.mjs` | Ten named scenario methods (Chapter 22) |

Acceptance criteria ids (`AC-1` and so on, Chapter 2) are intended to appear in test display names or comments; the matrix in `docs/traceability/` and the test sources are the places to look up a specific id.

Test classes are in `backend/src/test/java/com/example/messaging/` under the packages `batch`, `callback`, `common`, `coordination`, `diagnostics`, `domain`, `messaging`, `pipeline`, `provider`, `retention`, `scheduler`, `security`, `service`, `template` and `web`. Frontend tests are the `*.spec.ts` files in `frontend/messaging-console/src/app/`.
