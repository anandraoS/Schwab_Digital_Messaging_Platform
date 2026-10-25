// Builds docs/pdf/Schwab_Digital_Messaging_Architecture_and_Workflows.pdf (PDF 1).
// Usage (from tools/pdf):  node pdf1/build-pdf1.mjs      or      npm run pdf1
// Chapter text lives in pdf1/content/*.md. Two expansion tokens are supported inside those files:
//   {{diagram:key}}                      -> a ```diagram fence built from pdf1/diagrams.mjs
//   {{file:relative/path.txt}}           -> the file verbatim in a code block
//   {{embed:relative/path.md|opt=...}}   -> a repository document inlined with demoted headings
//        options: title=Text (replaces the document title), skip=Heading A;Heading B (sections to drop),
//                 only=Heading A;Heading B (keep just these sections), note=Text (italic lead-in)
// After each build the PDF is scanned for headings stranded at the bottom of a page (the renderer keeps headings with
// the next paragraph but not with a figure that moves to the next page); a page break is then inserted before such a
// heading and the document is rebuilt (at most 4 passes).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildDocument } from '../lib/render.mjs';
import { DIAGRAMS } from './diagrams.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const contentDir = path.join(here, 'content');
const outFile = path.join(root, 'docs', 'pdf', 'Schwab_Digital_Messaging_Architecture_and_Workflows.pdf');

// ------------------------------------------------------------------ embedding helpers
const FENCE = /^\s*(```|~~~)/;

function escapePipesInCodeSpans(line) {
  if (!line.trimStart().startsWith('|')) return line;
  let out = '';
  let inCode = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '`') inCode = !inCode;
    if (ch === '|' && inCode && line[i - 1] !== '\\') out += '\\|';
    else out += ch;
  }
  return out;
}

function stripLinks(line) {
  // [text](relative/path) -> text ; keep http(s) links and in-document anchors
  return line.replace(/\[([^\]]+)\]\((?!https?:|#)[^)]*\)/g, '$1');
}

function splitSections(lines) {
  // returns [{level, title, lines[]}] at heading level 2 (outside fences); index 0 = preamble (level 0)
  const sections = [{ level: 0, title: '', lines: [] }];
  let inFence = false;
  for (const l of lines) {
    if (FENCE.test(l)) inFence = !inFence;
    const m = !inFence && /^##\s+(.*?)\s*#*\s*$/.exec(l);
    if (m) sections.push({ level: 2, title: m[1], lines: [l] });
    else sections[sections.length - 1].lines.push(l);
  }
  return sections;
}

export function embedDoc(rel, opts = {}) {
  const file = path.join(root, rel);
  if (!fs.existsSync(file)) {
    console.warn(`[pdf1] embed missing: ${rel}`);
    return `> [!WARNING] Missing source\n> The source document \`${rel}\` was not found when this PDF was built.\n`;
  }
  let lines = fs.readFileSync(file, 'utf8').replace(/\r\n?/g, '\n').split('\n');
  let docTitle = path.basename(rel);
  const h1 = lines.findIndex((l) => /^#\s+/.test(l));
  if (h1 >= 0) {
    docTitle = lines[h1].replace(/^#\s+/, '').trim();
    lines.splice(h1, 1);
  }
  const skip = (opts.skip || '').split(';').map((s) => s.trim().toLowerCase()).filter(Boolean);
  const only = (opts.only || '').split(';').map((s) => s.trim().toLowerCase()).filter(Boolean);
  if (skip.length || only.length) {
    const secs = splitSections(lines);
    lines = [];
    for (const s of secs) {
      const t = s.title.toLowerCase();
      if (s.level === 0) { if (!only.length) lines.push(...s.lines); continue; }
      if (skip.some((k) => t.startsWith(k))) continue;
      if (only.length && !only.some((k) => t.startsWith(k))) continue;
      lines.push(...s.lines);
    }
  }
  const out = [];
  let inFence = false;
  let fenceLang = '';
  let skipFence = false;
  for (const raw of lines) {
    const fm = FENCE.exec(raw);
    if (fm) {
      if (!inFence) {
        inFence = true;
        fenceLang = raw.trim().replace(/^(```|~~~)\s*/, '').toLowerCase();
        if (fenceLang.startsWith('mermaid')) {
          skipFence = true;
          out.push('*A Mermaid source diagram that lives in the repository document is replaced in this PDF by the native figures in this chapter.*');
          continue;
        }
      } else {
        inFence = false;
        if (skipFence) { skipFence = false; continue; }
      }
      out.push(raw);
      continue;
    }
    if (skipFence) continue;
    if (inFence) { out.push(raw); continue; }
    const hm = /^(#{1,6})\s+(.*)$/.exec(raw);
    if (hm) {
      const lvl = Math.min(hm[1].length + 1, 4);
      out.push(`${'#'.repeat(lvl)} ${hm[2]}`);
      continue;
    }
    out.push(escapePipesInCodeSpans(stripLinks(raw)));
  }
  const title = opts.title || docTitle;
  const head = [`## ${title}`, '', `*Source document: \`${rel}\`*`, ''];
  if (opts.note) head.push(`*${opts.note}*`, '');
  return head.join('\n') + out.join('\n') + '\n';
}

function expandTokens(text, fileName) {
  return text.replace(/\{\{(diagram|embed|file):([^}]+)\}\}/g, (all, kind, arg) => {
    if (kind === 'file') {
      const f = path.join(root, arg.trim());
      if (!fs.existsSync(f)) { console.warn(`[pdf1] missing file ${arg}`); return `> [!WARNING] Missing file ${arg}`; }
      return '```text\n' + fs.readFileSync(f, 'utf8').replace(/\r\n?/g, '\n').replace(/\n+$/, '') + '\n```';
    }
    if (kind === 'diagram') {
      const spec = DIAGRAMS[arg.trim()];
      if (!spec) { console.warn(`[pdf1] ${fileName}: unknown diagram ${arg}`); return `> [!WARNING] Missing diagram ${arg}`; }
      return '```diagram ' + JSON.stringify(spec) + '\n```';
    }
    const [rel, ...optParts] = arg.split('|');
    const opts = {};
    for (const p of optParts) {
      const i = p.indexOf('=');
      if (i > 0) opts[p.slice(0, i).trim()] = p.slice(i + 1).trim();
    }
    return embedDoc(rel.trim(), opts);
  });
}

// ------------------------------------------------------------------ orphan-heading repair
const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '');

/** Insert a page break before every (h1, heading) pair listed in `breaks` (keys "h1norm|headingnorm"). */
function applyBreaks(text, breaks) {
  if (!breaks.size) return text;
  const lines = text.split('\n');
  const out = [];
  let inFence = false;
  let h1 = '';
  for (const l of lines) {
    if (FENCE.test(l)) inFence = !inFence;
    if (!inFence) {
      const m1 = /^#\s+(.*)$/.exec(l);
      if (m1) h1 = norm(m1[1]);
      const m = /^#{2,4}\s+(.*)$/.exec(l);
      if (m && breaks.has(`${h1}|${norm(m[1])}`)) out.push('<!-- pagebreak -->', '');
    }
    out.push(l);
  }
  return out.join('\n');
}

async function findStrandedHeadings(pdfFile) {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const doc = await pdfjs.getDocument({ data: new Uint8Array(fs.readFileSync(pdfFile)), verbosity: 0 }).promise;
  const found = [];
  for (let p = 2; p <= doc.numPages; p++) {
    const page = await doc.getPage(p);
    const vp = page.getViewport({ scale: 1 });
    const tc = await page.getTextContent();
    const body = [];
    let header = '';
    for (const it of tc.items) {
      if (!('str' in it) || !it.str.trim()) continue;
      const yTop = vp.height - it.transform[5];
      if (yTop <= 52) header = it.str.trim();
      else if (yTop < vp.height - 66) body.push({ y: yTop, size: it.transform[0], str: it.str });
    }
    if (!body.length) continue;
    let maxY = Math.max(...body.map((b) => b.y));
    let last = body.filter((b) => Math.abs(b.y - maxY) < 3);
    // an embedded document starts with "## Title" plus a "Source document:" line: judge the heading above that line
    if (/^Source document/.test(last.map((b) => b.str).join(' ').trim())) {
      const above = body.filter((b) => b.y < maxY - 3);
      if (above.length) {
        maxY = Math.max(...above.map((b) => b.y));
        last = body.filter((b) => Math.abs(b.y - maxY) < 3);
      }
    }
    const size = Math.max(...last.map((b) => b.size));
    const minY = Math.min(...body.map((b) => b.y));
    if (size >= 12.5 && size < 22) {
      const text = last.map((b) => b.str).join(' ');
      found.push({ page: p, h1: norm(header), heading: norm(text), text, atTop: maxY - minY < 30 });
    }
  }
  return found;
}

// ------------------------------------------------------------------ assemble
const files = fs.readdirSync(contentDir).filter((f) => f.endsWith('.md')).sort();
const expanded = files.map((f) => expandTokens(fs.readFileSync(path.join(contentDir, f), 'utf8'), f));

async function build(breaks) {
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  return buildDocument({
    title: 'Digital Messaging Platform: Architecture and Workflows',
    subtitle: 'Enterprise omnichannel messaging and notification orchestration (EMAIL, SMS, PUSH): a reference implementation',
    version: 'v1.0',
    date: '2026-10-18',
    author: 'Reference implementation team, authored with Claude Code',
    disclaimer: 'Independent reference implementation. All data is synthetic and no real provider is called. Validation status of every claim is stated explicitly; anything not executed (Docker, Kubernetes, Cloud Foundry, k6, RabbitMQ, Redis, real PostgreSQL, CI servers) is labelled NOT executed. Illustrative numbers are marked illustrative.',
    outFile,
    toc: true,
    tocDepth: 2,
    pageSize: 'LETTER',
    header: 'Digital Messaging Platform: Architecture and Workflows',
    baseDir: root,
    parts: expanded.map((t) => ({ type: 'markdown', text: applyBreaks(t, breaks) })),
  });
}

const breaks = new Set();
let res = await build(breaks);
for (let pass = 1; pass <= 4; pass++) {
  const stranded = await findStrandedHeadings(outFile);
  const fixable = stranded.filter((s) => !s.atTop && !breaks.has(`${s.h1}|${s.heading}`));
  const unfixable = stranded.filter((s) => s.atTop);
  if (unfixable.length) console.warn('[pdf1] heading-only pages (restructure content):', unfixable.map((s) => `${s.page}:${s.text}`).join(' | '));
  if (!fixable.length) break;
  for (const s of fixable) breaks.add(`${s.h1}|${s.heading}`);
  console.log(`[pdf1] pass ${pass}: page break before ${fixable.length} stranded heading(s): ${fixable.map((s) => s.text).join(' | ')}`);
  res = await build(breaks);
}
console.log(JSON.stringify({ outFile: res.outFile, pages: res.pages, ms: res.ms, warnings: res.warnings, stats: res.stats, pageBreaksAdded: breaks.size }, null, 2));
