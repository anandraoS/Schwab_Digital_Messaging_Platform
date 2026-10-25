// One-off, idempotent: groups each question bank into topic sections with `## ` headings (they become TOC and
// outline entries). Question numbers and order are unchanged. Usage: node tools/pdf/pdf2/insert-topic-headings.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const IV = path.resolve(here, '..', '..', '..', 'docs', 'interview');

const plan = {
  '01-java-spring-questions.md': [[1, 'JVM, Memory and Concurrency'], [10, 'Spring Core, Transactions and Hibernate'], [18, 'Exceptions, Testing, Configuration and Design']],
  '02-rest-and-channels-questions.md': [[25, 'REST API Design'], [34, 'Email, SMS and Push Channels'], [43, 'Policy, Fallback and Provider Abstraction']],
  '03-distributed-messaging-questions.md': [[47, 'Platform Design and Delivery Semantics'], [50, 'Duplicates, Ambiguous Outcomes and Webhooks'], [54, 'Ordering, Retry, DLQ, Backpressure and Resilience'], [61, 'Brokers, Reconciliation and Failure Scenarios']],
  '04-batch-and-data-questions.md': [[71, 'Spring Batch'], [80, 'SQL, Isolation and Oracle'], [89, 'Data Stores, Connection Pools and Migrations']],
  '05-platform-and-security-questions.md': [[95, 'Cloud Platforms, Containers and CI/CD'], [108, 'Security']],
  '06-performance-observability-ai-questions.md': [[119, 'Performance'], [124, 'SRE and Observability'], [133, 'AI-Assisted Development']],
  '07-leadership-and-fintech-questions.md': [[143, 'Leadership and Delivery'], [152, 'Financial Services Engineering'], [158, 'Onboarding, Architecture and Full-Stack Delivery']],
  '08-additional-scenarios-questions.md': [[162, 'Caching, Consistency, Migrations, Performance, Pipelines and Safe AI Change']],
};

let added = 0;
for (const [file, groups] of Object.entries(plan)) {
  const p = path.join(IV, file);
  const lines = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n').split('\n');
  for (const [num, title] of groups) {
    const tag = `### Q${String(num).padStart(2, '0')}.`;
    const i = lines.findIndex((l) => l.startsWith(tag));
    if (i < 0) throw new Error(`${tag} not found in ${file}`);
    if (lines[i - 2] === `## ${title}` || lines[i - 1] === `## ${title}`) continue;
    lines.splice(i, 0, `## ${title}`, '');
    added++;
  }
  fs.writeFileSync(p, lines.join('\n'));
}
console.log(`inserted ${added} topic headings`);
