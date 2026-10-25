#!/usr/bin/env node
// Usage: node verify-pdf.mjs <pdf> [--min-pages N] [--expect-text "..."]... [--allow-no-outline] [--blank-threshold N] [--dump-page N]
import { verifyPdf, formatReport } from './lib/verify.mjs';

const args = process.argv.slice(2);
const opts = { expectText: [] };
let file = null, dump = null;
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === '--min-pages') opts.minPages = parseInt(args[++i], 10);
  else if (a === '--expect-text') opts.expectText.push(args[++i]);
  else if (a === '--allow-no-outline') opts.allowNoOutline = true;
  else if (a === '--blank-threshold') opts.blankThreshold = parseInt(args[++i], 10);
  else if (a === '--dump-page') dump = parseInt(args[++i], 10);
  else if (!file) file = a;
}
if (!file) { console.error('usage: node verify-pdf.mjs <pdf> [--min-pages N] [--expect-text "..."]...'); process.exit(2); }
try {
  const r = await verifyPdf(file, opts);
  console.log(formatReport(file, r));
  if (dump) console.log(`\n--- page ${dump} text ---\n${r.pages[dump - 1]?.text}`);
  process.exit(r.ok ? 0 : 1);
} catch (e) {
  console.error('verify error:', e.stack || e);
  process.exit(2);
}
