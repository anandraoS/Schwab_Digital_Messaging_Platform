// Builds out/sample.pdf exercising every engine feature.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildDocument } from '../lib/render.mjs';
import { loadMarkdownDir } from '../lib/md.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const content = path.join(here, 'content');

// ---- generated content: long code, long tables, very wide table
const javaLines = [];
javaLines.push('package com.example.messaging.service;', '', 'import java.time.Duration;', 'import java.util.*;', '');
javaLines.push('/** Dispatch consumer: processes one envelope; all state changes go through MessageStateMachine. */');
javaLines.push('@Service', 'public class DispatchService {');
for (let i = 1; i <= 70; i++) {
  javaLines.push(`\t// step ${i}: tab-indented comment with arrows → ⇒ and “quotes”`);
  javaLines.push(`    public ProviderResult stage${i}(DispatchEnvelope envelope, Map<String, Object> context, Duration timeout) throws InterruptedException {`);
  javaLines.push(`        if (envelope.attempt() > ${i}) { return ProviderResult.retryLater("backoff-" + ${i} + "-exceeded-the-allowed-budget-for-this-envelope-and-tenant-configuration"); }`);
  javaLines.push('        return ProviderResult.accepted();', '    }', '');
}
javaLines.push('}');

const sqlLines = ['-- Flyway V1: runs on PostgreSQL and on H2 in MODE=PostgreSQL', 'CREATE TABLE message (', '  id VARCHAR(36) NOT NULL PRIMARY KEY,', '  tenant_id VARCHAR(36) NOT NULL,', '  status VARCHAR(24) NOT NULL,', '  created_at TIMESTAMP NOT NULL', ');', 'CREATE UNIQUE INDEX ux_message_idem ON message (tenant_id, idempotency_key);'];

const statuses = ['ACCEPTED', 'VALIDATED', 'QUEUED', 'PROCESSING', 'SENT_TO_PROVIDER', 'DELIVERED', 'RETRY_PENDING', 'FAILED'];
let longTable = '| # | Message id | Channel | Status | Notes |\n|--:|---|:-:|---|---|\n';
for (let i = 1; i <= 90; i++) {
  longTable += `| ${i} | msg-${String(i).padStart(5, '0')}-${(i * 7919).toString(16)} | ${['EMAIL', 'SMS', 'PUSH'][i % 3]} | ${statuses[i % statuses.length]} | ${i % 9 === 0 ? 'A deliberately long note that wraps across several lines inside the cell to prove that rows grow and that the header repeats after the page break. `code` and **bold** work here too.' : 'ok'} |\n`;
}

let wide = '| ' + Array.from({ length: 14 }, (_, i) => `Column ${i + 1}`).join(' | ') + ' |\n|' + '---|'.repeat(14) + '\n';
for (let r = 0; r < 8; r++) wide += '| ' + Array.from({ length: 14 }, (_, c) => (c === 3 ? `value-${r}-with-a-long-token-${c}` : `r${r}c${c}`)).join(' | ') + ' |\n';

const stressText = `# Code, Tables and Stress Cases

## Long Code Block

The block below has ${javaLines.length} lines, tabs, unicode arrows and very long lines. It must split across pages and wrap long lines.

\`\`\`java
${javaLines.join('\n')}
\`\`\`

## SQL

\`\`\`sql
${sqlLines.join('\n')}
\`\`\`

\`\`\`
plain block without a language tag: no label strip is drawn
\`\`\`

## Simple Table

| Channel | Priority | Retry budget | Notes |
|---|:-:|--:|---|
| EMAIL | NORMAL | 5 | Provider accepted does not mean delivered |
| SMS | HIGH | 3 | Carrier receipts may arrive late |
| PUSH | CRITICAL | 2 | Token invalidation is a permanent failure |

## Very Wide Table (shrinks font)

${wide}

## Long Table (header repeats across pages)

${longTable}

## Callout With Long Content

> [!WARNING] A warning that spans a page break
> ${Array.from({ length: 6 }, (_, i) => `Paragraph ${i + 1} inside the callout. `.repeat(18)).join('\n>\n> ')}

## Blockquote With Long Content

> ${Array.from({ length: 4 }, (_, i) => `Quoted paragraph ${i + 1}. `.repeat(20)).join('\n>\n> ')}

## Dense Paragraphs

${Array.from({ length: 6 }, (_, i) => `Paragraph ${i + 1}. The quick brown fox jumps over the lazy dog while **bold phrases**, *italic phrases* and \`inline_code_with_underscores\` are mixed in. `.repeat(8)).join('\n\n')}
`;

const parts = [
  ...loadMarkdownDir(content, { order: ['01-introduction', '02-architecture', '03-sequence-er-mindmap-charts'], strict: true }),
  { type: 'markdown', text: stressText },
  { type: 'markdown', text: '# Interview Preparation\n\nThe questions below use the structured interview format.' },
  { type: 'interview', files: [path.join(content, 'interview.md')] },
  { type: 'markdown', text: '# Standalone Diagram Part\n\nThe diagram below is injected as a `diagram` part instead of markdown.' },
  { type: 'diagram', caption: 'Programmatic diagram part (bar chart)', spec: { type: 'bar', orientation: 'horizontal', data: [{ label: 'alpha', value: 3 }, { label: 'beta', value: 7 }, { label: 'gamma', value: 5 }] } },
];

const res = await buildDocument({
  title: 'PDF Engine Sample Document',
  subtitle: 'Markdown, diagrams, tables, code and interview Q&A in one reusable renderer',
  version: 'v1.0.0',
  date: '2026-10-18',
  outFile: path.join(here, '..', 'out', 'sample.pdf'),
  parts,
});
console.log(JSON.stringify({ outFile: res.outFile, pages: res.pages, ms: res.ms, fonts: res.fonts.families, stats: res.stats, warnings: res.warnings }, null, 2));
