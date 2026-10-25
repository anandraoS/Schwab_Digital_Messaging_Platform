#!/usr/bin/env node
// Parses YAML/JSON files (syntax check only). Usage: node scripts/lib/validate-files.mjs <file-or-dir> ...
// Skips Helm templates (Go templating), node_modules, target, .git, tools. Exit 1 if any file fails.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname, sep } from 'node:path';
import { parseYamlDocs } from './yaml-lite.mjs';

const SKIP_DIRS = new Set(['node_modules', 'target', '.git', '.angular', 'dist', '.work', 'tools']);
function* walk(p) {
  const st = statSync(p);
  if (st.isFile()) { yield p; return; }
  for (const e of readdirSync(p)) {
    if (SKIP_DIRS.has(e)) continue;
    yield* walk(join(p, e));
  }
}
let ok = 0, fail = 0, skipped = 0;
for (const arg of process.argv.slice(2)) {
  for (const f of walk(arg)) {
    const ext = extname(f).toLowerCase();
    if (!['.yml', '.yaml', '.json'].includes(ext)) continue;
    if (f.split(sep).join('/').includes('/helm/') && f.split(sep).join('/').includes('/templates/')) { skipped++; continue; }
    try {
      const text = readFileSync(f, 'utf8');
      if (ext === '.json') JSON.parse(text); else parseYamlDocs(text);
      ok++; console.log(`OK   ${f}`);
    } catch (e) { fail++; console.log(`FAIL ${f}: ${e.message}`); }
  }
}
console.log(`summary: parsed_ok=${ok} failed=${fail} skipped_helm_templates=${skipped}`);
process.exit(fail ? 1 : 0);
