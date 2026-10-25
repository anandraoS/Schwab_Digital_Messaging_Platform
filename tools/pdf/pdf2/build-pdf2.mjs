// Builds docs/pdf/Schwab_Senior_Java_Engineer_Deep_Dive_and_Interview_Guide.pdf
// Usage: node tools/pdf/pdf2/build-pdf2.mjs   (or: npm run pdf2 inside tools/pdf)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildDocument } from '../lib/render.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..', '..');
const posix = (p) => p.replace(/\\/g, '/');
const iv = (...p) => posix(path.join(root, 'docs', 'interview', ...p));
const content = (f) => posix(path.join(here, 'content', f));
const outFile = posix(path.join(root, 'docs', 'pdf', 'Schwab_Senior_Java_Engineer_Deep_Dive_and_Interview_Guide.pdf'));
fs.mkdirSync(path.dirname(outFile), { recursive: true });

const md = (file) => {
  if (!fs.existsSync(file)) throw new Error(`missing content file: ${file}`);
  return { type: 'markdown', file };
};
const divider = (title, intro, rows) => ({
  type: 'markdown',
  text: `# ${title}\n\n${intro}\n\n| Chapter | What you get |\n|---|---|\n${rows.map((r) => `| ${r[0]} | ${r[1]} |`).join('\n')}\n`,
});

const parts = [
  // ---------------------------------------------------------------- front matter
  md(content('01-about-this-guide.md')),
  md(content('02-study-plan.md')),
  md(iv('00-how-to-use.md')),
  md(content('03-project-map.md')),
  md(content('04-talking-points.md')),
  md(content('05-diagram-atlas.md')),

  // ---------------------------------------------------------------- Part I
  divider('Part I: Java and Spring Boot', 'The language, runtime and framework base: what the JVM does with memory, how threads and pools behave, how Spring wires and proxies beans, how transactions and Hibernate really work, and how to design a REST API and its errors.', [
    ['Java, JVM, Garbage Collection and Concurrency', 'Memory areas, G1 and ZGC, thread pools, CompletableFuture, virtual threads, the memory model, diagnosis with jstack and JFR'],
    ['Spring DI, Transactions and Hibernate', 'Injection, proxies, propagation and rollback rules, N+1, locking, batching, test slices'],
    ['REST API Design and Error Handling', 'Resources, status codes, the 202 pattern, idempotency keys, pagination, versioning, the error model, rate limiting'],
  ]),
  md(iv('deep-dive', 'java-jvm-gc-concurrency.md')),
  md(iv('deep-dive', 'spring-di-transactions-hibernate.md')),
  md(iv('deep-dive', 'rest-api-design-and-errors.md')),

  // ---------------------------------------------------------------- Part II
  divider('Part II: Reliable Messaging at Scale', 'The reliability toolbox of a notification platform: delivery guarantees, idempotent consumers, the outbox, ordering, retries, dead letters, backpressure, circuit breakers, unknown outcomes and reconciliation.', [
    ['Messaging and Distributed Systems', 'Delivery semantics, outbox, ordering, retry with jitter, DLQ, backpressure, circuit breaker, ambiguous outcomes, Kafka versus RabbitMQ, channel specifics'],
  ]),
  md(iv('deep-dive', 'messaging-and-distributed-systems.md')),

  // ---------------------------------------------------------------- Part III
  divider('Part III: Databases and Data', 'Relational design and its limits, Oracle versus PostgreSQL, where MongoDB fits, indexing and query plans, isolation, connection pools, caching, schema migrations and data retention.', [
    ['Databases: Relational Fundamentals, Oracle and MongoDB', 'Indexes, plans, isolation, deadlocks, pagination, partitioning, Oracle specifics, MongoDB, choosing stores'],
    ['Caching and Connection Pools', 'Cache patterns and stampedes, Redis as a dependency, pool sizing with Little\'s Law, reading a pool incident'],
    ['Schema Migrations, Retention and Archival', 'Flyway, expand and contract, retention with legal hold, batched delete versus partition drop, erasure'],
  ]),
  md(iv('deep-dive', 'databases-oracle-mongo.md')),
  md(iv('deep-dive', 'caching-and-connection-pools.md')),
  md(iv('deep-dive', 'schema-migrations-retention.md')),

  // ---------------------------------------------------------------- Part IV
  divider('Part IV: Cloud, DevOps and Operations', 'Packaging, deploying and running the service: Docker, Cloud Foundry, Kubernetes, pipelines in Jenkins and Bamboo, rollout and rollback, then observing it, setting objectives and testing performance.', [
    ['Cloud and DevOps: Docker, Cloud Foundry, Kubernetes, CI/CD', 'Twelve-factor mapping, Dockerfile, manifests, probes, graceful shutdown, pipelines, rollback strategies'],
    ['Observability, SRE and Performance', 'Metrics, logs, traces, SLIs and SLOs, error budgets, runbooks, Little\'s Law, load testing method'],
  ]),
  md(iv('deep-dive', 'cloud-devops-pcf-k8s.md')),
  md(iv('deep-dive', 'observability-sre-performance.md')),

  // ---------------------------------------------------------------- Part V
  divider('Part V: Security', 'Authentication and authorisation with OAuth2 and JWT, the OWASP API Security Top 10, signed webhooks, secrets, data minimisation, audit and safe templates.', [
    ['Security: OAuth2, OIDC, JWT, OWASP, Webhooks, Data Protection', 'Flows, JWT pitfalls, Spring Security 6, tenant isolation, HMAC verification, secrets, masking, mTLS, audit, STRIDE'],
  ]),
  md(iv('deep-dive', 'security-oauth-owasp.md')),

  // ---------------------------------------------------------------- Part VI
  divider('Part VI: AI-Assisted Engineering', 'Using AI coding assistants as a senior engineer: workflows, specifications, reusable context, token economy, reviewing generated code, multi-agent work with approval gates, and safe refactoring and upgrades.', [
    ['AI-Assisted Engineering: Copilot, Specs, Context, Agents, Guardrails', 'Where AI helps, instruction files, spec-driven development, context engineering, review, prompt injection, measuring'],
    ['AI-Assisted Safe Refactoring and Dependency Upgrades', 'Behaviour first, small steps, upgrade process, hallucination controls, human approval'],
  ]),
  md(iv('deep-dive', 'ai-assisted-engineering.md')),
  md(iv('deep-dive', 'ai-safe-refactoring-and-upgrades.md')),

  // ---------------------------------------------------------------- Part VII
  divider('Part VII: System Design and Revision', 'Whiteboard walkthroughs with capacity arithmetic, and one-page tables for last-minute review.', [
    ['System Design Walkthroughs', 'Four full designs with requirements, estimates, architecture, failure modes and trade-offs'],
    ['Cheat Sheets', 'Status codes, state machine, metrics, defaults and comparisons on single pages'],
  ]),
  md(iv('system-design-walkthroughs.md')),
  md(iv('cheat-sheets.md')),

  // ---------------------------------------------------------------- Part VIII
  divider('Part VIII: Interview Question Bank', 'Questions with model answers, organised by topic. Each has tags (topic, level, concept or scenario), a plain-language analogy, a model answer with trade-offs, follow-ups and pitfalls. Boxes titled "In this project" point at real files.', [
    ['Bank 1: Java, JVM, Spring, JPA (Q01 to Q24)', 'GC, threads, transactions, Hibernate, exceptions, testing, configuration, state machines'],
    ['Bank 2: REST and Channels (Q25 to Q46)', 'API design, idempotency, rate limiting, e-mail, SMS, push, policy, provider abstraction'],
    ['Bank 3: Distributed Messaging (Q47 to Q70)', 'Platform design, queues, duplicates, retries, DLQ, backpressure, breakers, reconciliation, fairness'],
    ['Bank 4: Batch and Data (Q71 to Q94)', 'Spring Batch, SQL, isolation, Oracle, MongoDB, pools, migrations'],
    ['Bank 5: Platform and Security (Q95 to Q118)', 'PCF, Kubernetes, Docker, CI/CD, OAuth2, JWT, OWASP, webhooks, secrets, templates'],
    ['Bank 6: Performance, Observability, AI (Q119 to Q142)', 'Performance, SRE, observability, AI-assisted development'],
    ['Bank 7: Leadership and Financial Services (Q143 to Q161)', 'Mentoring, decisions, delivery, regulated environments, full-stack delivery'],
    ['Bank 8: Additional Scenarios (Q162 to Q172)', 'Caching, eventual consistency, migrations, performance plan, pipelines, safe AI change'],
  ]),
  {
    type: 'interview',
    files: [
      iv('01-java-spring-questions.md'),
      iv('02-rest-and-channels-questions.md'),
      iv('03-distributed-messaging-questions.md'),
      iv('04-batch-and-data-questions.md'),
      iv('05-platform-and-security-questions.md'),
      iv('06-performance-observability-ai-questions.md'),
      iv('07-leadership-and-fintech-questions.md'),
      iv('08-additional-scenarios-questions.md'),
    ],
  },

  // ---------------------------------------------------------------- appendix
  md(iv('glossary.md')),
];

const res = await buildDocument({
  title: 'Senior Java Engineer Deep Dive and Interview Guide',
  subtitle: 'Digital Messaging Platform reference project: Java, Spring Boot, messaging, data, cloud, security and AI-assisted engineering',
  version: 'v1.0',
  date: '2026-10-18',
  author: 'Digital Messaging Platform project',
  disclaimer: 'Independent reference implementation with synthetic data only; it does not reproduce any employer\'s internal systems. Docker, Kubernetes, Cloud Foundry, Jenkins, Bamboo, k6, RabbitMQ, Redis, real PostgreSQL and real MongoDB were not executed; this guide quotes no performance measurements.',
  outFile,
  toc: true,
  tocDepth: 2,
  pageSize: 'LETTER',
  header: 'Senior Java Engineer Deep Dive and Interview Guide',
  baseDir: posix(path.join(root, 'docs', 'interview')),
  parts,
});

console.log(JSON.stringify({ outFile: res.outFile, pages: res.pages, ms: res.ms, warnings: res.warnings, stats: res.stats }, null, 2));
if (res.warnings && res.warnings.length) process.exitCode = 0;
