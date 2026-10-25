# 8. Resource Leak

Invariants: 4; performance checklist (pool, memory).

## Flawed code
```java
public BulkResult importCsv(Path file) throws IOException {
    BufferedReader reader = Files.newBufferedReader(file);
    List<String> lines = reader.lines().toList();                 // loads whole file; reader never closed on exception
    Connection c = dataSource.getConnection();                    // manual connection never closed
    for (String line : lines) { insert(c, parse(line)); }         // one transaction for everything
    return BulkResult.ok(lines.size());
}
```
## Why it is wrong
- Reader and Connection leak on exceptions (pool exhaustion under repeated failures: `hikaricp_connections_active` climbs to max, API 503).
- Whole file in memory (OOM for large CSV); no chunking so a failure at row N loses all work and holds locks.
- Bypasses Spring Batch restartability.

## Corrected version
```java
// Spring Batch step: FlatFileItemReader streams; chunk(500) transaction per chunk; framework manages resources
@Bean Step readValidateStep(JobRepository jr, PlatformTransactionManager tx, FlatFileItemReader<Row> reader,
                            RecipientRowProcessor p, BulkMessageWriter w) {
    return new StepBuilder("readValidateStep", jr).<Row, MessageDraft>chunk(500, tx)
        .reader(reader).processor(p).writer(w)
        .faultTolerant().skipLimit(cfg.skipLimit()).skip(InvalidRowException.class)
        .retryLimit(3).retry(TransientDataAccessException.class).build();
}
// any manual resource use:
try (var in = Files.newBufferedReader(file)) { ... }
```
## Rationale
Frameworks and try-with-resources guarantee release; streaming and chunking bound memory and make restart cheap.

## Regression tests
- `BulkJobIT#largeFile_streamed_heapBounded` (generate N rows with `SyntheticDatasetGenerator`, assert completion with a small heap setting or assert reader is not materialized).
- `BulkJobIT#writerFailure_releasesConnections` (Hikari active count returns to 0 via `HikariPoolMXBean`).
- `FailureRecoveryIT#scenario09_bulkJobRestart_resumesWithoutDuplicates`.
