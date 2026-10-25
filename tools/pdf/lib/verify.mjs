// PDF verification using pdfjs-dist (pinned): page count, per-page text, mandatory footer on every page,
// embedded fonts, outline, blank pages, text overflow (x positions vs page bounds), outline->page targets.
import fs from 'node:fs';
import { FOOTER_LINES } from './footer.mjs';

const norm = (s) => s.replace(/\s+/g, '');

export async function verifyPdf(file, opts = {}) {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const bytes = new Uint8Array(fs.readFileSync(file));
  const raw = Buffer.from(bytes).toString('latin1');
  const doc = await pdfjs.getDocument({ data: bytes, verbosity: 0, useSystemFonts: false, disableFontFace: true }).promise;
  const failures = [], warnings = [];
  const footerLines = opts.footerLines || FOOTER_LINES;
  const fl = footerLines.map(norm);
  const blankThreshold = opts.blankThreshold ?? 30;
  const pages = [];
  const margin = opts.overflowMargin ?? 6;
  const stats = { pages: doc.numPages, footerMissing: [], blank: [], overflow: [], textChars: 0 };

  for (let p = 1; p <= doc.numPages; p++) {
    const page = await doc.getPage(p);
    const vp = page.getViewport({ scale: 1 });
    const tc = await page.getTextContent();
    let all = '', body = '';
    let overflowItems = [];
    for (const it of tc.items) {
      if (!('str' in it)) continue;
      all += it.str + (it.hasEOL ? '\n' : ' ');
      const x = it.transform[4], yTop = vp.height - it.transform[5];
      const w = it.width || 0;
      if (it.str.trim() && (x < margin || x + w > vp.width - margin)) overflowItems.push({ str: it.str.slice(0, 40), x: Math.round(x), r: Math.round(x + w) });
      if (yTop > 52 && yTop < vp.height - 66) body += it.str + ' ';
    }
    const n = norm(all);
    const missing = fl.map((f, i) => (n.includes(f) ? null : i + 1)).filter(Boolean);
    if (missing.length) { stats.footerMissing.push(p); }
    const bodyLen = body.replace(/\s+/g, '').length;
    if (bodyLen < blankThreshold) stats.blank.push(p);
    if (overflowItems.length) stats.overflow.push({ page: p, items: overflowItems.slice(0, 3) });
    stats.textChars += n.length;
    pages.push({ page: p, text: all, bodyLen });
    page.cleanup();
  }
  if (opts.minPages && doc.numPages < opts.minPages) failures.push(`page count ${doc.numPages} < required ${opts.minPages}`);
  if (stats.footerMissing.length) failures.push(`footer text missing on ${stats.footerMissing.length} page(s): ${stats.footerMissing.slice(0, 15).join(',')}`);
  if (stats.blank.length) failures.push(`blank/near-blank pages (body < ${blankThreshold} chars): ${stats.blank.slice(0, 15).join(',')}`);
  if (stats.overflow.length) failures.push(`possible text overflow on ${stats.overflow.length} page(s): ` + stats.overflow.slice(0, 5).map((o) => `p${o.page} "${o.items[0].str}" x=${o.items[0].x}..${o.items[0].r}`).join('; '));

  // fonts (raw scan: pdfkit writes uncompressed dictionaries)
  const baseFonts = [...raw.matchAll(/\/BaseFont\s*\/([^\s/\[\]<>()]+)/g)].map((m) => m[1]);
  const uniq = [...new Set(baseFonts)];
  const nonEmbedded = uniq.filter((f) => !/^[A-Z]{6}\+/.test(f));
  const fontFiles = (raw.match(/\/FontFile[23]?\b/g) || []).length;
  stats.fonts = uniq; stats.fontFiles = fontFiles;
  if (!uniq.length) warnings.push('no fonts found by raw scan');
  if (nonEmbedded.length) failures.push(`non-embedded fonts: ${nonEmbedded.join(', ')}`);
  if (uniq.length && !fontFiles) failures.push('no embedded font programs (FontFile) found');

  // outline
  const outline = await doc.getOutline();
  stats.outlineTop = outline ? outline.length : 0;
  let outlineTotal = 0;
  const flat = [];
  const walk = (items, depth) => { for (const it of items || []) { outlineTotal++; flat.push({ ...it, depth }); walk(it.items, depth + 1); } };
  walk(outline, 0);
  stats.outlineTotal = outlineTotal;
  if (!outline || !outline.length) { if (!opts.allowNoOutline) failures.push('PDF outline (bookmarks) missing'); }
  // outline targets
  let checked = 0, bad = [];
  for (const it of flat) {
    if (it.depth > 1 || !it.dest) continue;
    try {
      const dest = typeof it.dest === 'string' ? await doc.getDestination(it.dest) : it.dest;
      if (!dest) { bad.push(`${it.title} (dest not found)`); continue; }
      const idx = await doc.getPageIndex(dest[0]);
      const t = norm(pages[idx].text), want = norm(it.title).slice(0, 24);
      checked++;
      if (want && !t.includes(want)) bad.push(`"${it.title}" -> p${idx + 1} (title text not on that page)`);
    } catch (e) { bad.push(`${it.title} (${e.message})`); }
  }
  stats.outlineChecked = checked;
  if (bad.length) failures.push(`outline targets wrong for ${bad.length} item(s): ${bad.slice(0, 4).join('; ')}`);
  // internal links: every /D (name) GoTo must resolve to a named destination
  const linkRe = new RegExp('/D \\(([^)\\r\\n]+)\\)', 'g');
  const linkNames = [...new Set([...raw.matchAll(linkRe)].map((m) => m[1]))];
  let brokenLinks = [];
  for (const nm of linkNames) { let d = null; try { d = await doc.getDestination(nm); } catch { d = null; } if (!d) brokenLinks.push(nm); }
  stats.internalLinks = linkNames.length; stats.brokenLinks = brokenLinks.length;
  if (brokenLinks.length) failures.push(`broken internal links: ${brokenLinks.slice(0, 5).join(', ')}`);

  // TOC numbers vs actual heading pages: parse "Title ....... N" pairs from TOC pages if a Contents page exists
  const tocItem = flat.find((f) => f.depth === 0 && f.title === 'Contents' && f.dest);
  let tocPage = -1;
  if (tocItem) { const dd = typeof tocItem.dest === 'string' ? await doc.getDestination(tocItem.dest) : tocItem.dest; if (dd) tocPage = await doc.getPageIndex(dd[0]); }
  if (tocPage >= 0) {
    const h1 = flat.filter((f) => f.depth === 0 && f.title !== 'Contents' && f.dest);
    let mism = 0, ck = 0;
    for (const it of h1) {
      const dest = typeof it.dest === 'string' ? await doc.getDestination(it.dest) : it.dest;
      if (!dest) continue;
      const idx = (await doc.getPageIndex(dest[0])) + 1;
      // find the TOC line for this title in TOC pages (consecutive pages that look like TOC)
      let found = false;
      for (let p = tocPage; p < Math.min(tocPage + 6, pages.length); p++) {
        const flatText = pages[p].text.replace(/\n/g, ' ');
        const k = flatText.indexOf(it.title.slice(0, 30));
        if (k >= 0) {
          const after = flatText.slice(k + it.title.length, k + it.title.length + 40);
          const m = /(\d+)\s/.exec(after);
          if (m) { ck++; found = true; if (parseInt(m[1], 10) !== idx) mism++; }
          break;
        }
      }
      void found;
    }
    stats.tocChecked = ck; stats.tocMismatch = mism;
    if (mism) failures.push(`TOC page numbers disagree with outline targets for ${mism}/${ck} h1 entries`);
  }

  const combined = pages.map((p) => p.text).join('\n');
  const combinedN = norm(combined);
  stats.expectMissing = [];
  for (const e of opts.expectText || []) if (!combinedN.includes(norm(e))) stats.expectMissing.push(e);
  if (stats.expectMissing.length) failures.push(`expected text not found: ${stats.expectMissing.map((s) => JSON.stringify(s)).join(', ')}`);
  const bad2 = (combined.match(/�/g) || []).length;
  if (bad2) warnings.push(`${bad2} replacement characters (U+FFFD) in extracted text`);
  const qmarks = (combined.match(/\?\?\?/g) || []).length;
  if (qmarks) warnings.push(`${qmarks} occurrences of "???" (possible unsupported glyph replacement)`);

  return { ok: failures.length === 0, failures, warnings, stats, pages, size: fs.statSync(file).size };
}

export function formatReport(file, r) {
  const s = r.stats, L = [];
  L.push(`PDF verification: ${file}`);
  L.push(`  size           : ${(r.size / 1024).toFixed(1)} KiB, pages: ${s.pages}`);
  L.push(`  footer         : ${s.footerMissing.length ? 'MISSING on ' + s.footerMissing.length + ' page(s)' : 'present on all ' + s.pages + ' pages (both lines)'}`);
  L.push(`  fonts          : ${(s.fonts || []).join(', ') || 'n/a'} (font programs: ${s.fontFiles})`);
  L.push(`  outline        : ${s.outlineTotal} entries (${s.outlineTop} top-level), targets checked: ${s.outlineChecked}, internal links: ${s.internalLinks} (broken ${s.brokenLinks})`);
  if (s.tocChecked !== undefined) L.push(`  toc            : ${s.tocChecked} h1 entries checked, ${s.tocMismatch} mismatched`);
  L.push(`  blank pages    : ${s.blank.length ? s.blank.join(',') : 'none'}`);
  L.push(`  text overflow  : ${s.overflow.length ? s.overflow.length + ' page(s)' : 'none'}`);
  L.push(`  text chars     : ${s.textChars}`);
  for (const w of r.warnings) L.push(`  WARN: ${w}`);
  for (const f of r.failures) L.push(`  FAIL: ${f}`);
  L.push(r.ok ? 'RESULT: PASS' : 'RESULT: FAIL');
  return L.join('\n');
}
