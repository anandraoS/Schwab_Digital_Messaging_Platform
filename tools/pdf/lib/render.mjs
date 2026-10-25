// Markdown-subset -> PDF renderer built on pdfkit. Two-pass (pass 1 to a null stream computes TOC page numbers).
import fs from 'node:fs';
import path from 'node:path';
import { Writable } from 'node:stream';
import PDFDocument from 'pdfkit';
import { registerFonts, safe, textWidth, ascent } from './fonts.mjs';
import { parseMarkdown, parseInline, slugify, plainText, readMarkdownFile } from './md.mjs';
import { FOOTER_LINES } from './footer.mjs';
import { renderDiagram, buildScene } from './diagrams.mjs';
import { parseInterview, interviewToBlocks, renderQuestion } from './interview.mjs';

export { FOOTER_LINES };

export const THEME = {
  navy: '#1F3A5F', accent: '#2E7D9A', text: '#222B33', gray: '#66717D', light: '#9AA5B1',
  link: '#1A5FB4', rule: '#C9D1D9', codeBg: '#F4F6F8', codeBorder: '#D5DBE1', codeText: '#1F2933',
  codeInline: '#9A2A5B', codeInlineBg: '#EEF1F4', tableHead: '#1F3A5F', zebra: '#F3F6FA', tableLine: '#CBD3DB',
  callouts: {
    note: { fill: '#EAF2FB', bar: '#2E78C7', title: '#1D4F8C', label: 'Note' },
    warning: { fill: '#FFF3DF', bar: '#E08A00', title: '#9A5B00', label: 'Warning' },
    tip: { fill: '#E9F6EC', bar: '#2E9D57', title: '#1D6B3A', label: 'Tip' },
    important: { fill: '#FDECEA', bar: '#C62828', title: '#962020', label: 'Important' },
  },
};

const PAGE_SIZES = { LETTER: [612, 792], A4: [595.28, 841.89] };
const nullStream = () => new Writable({ write(_c, _e, cb) { cb(); } });

export class Renderer {
  constructor(opts, pass, prev) {
    this.opts = opts; this.pass = pass; this.prev = prev;
    const [PW, PH] = PAGE_SIZES[opts.pageSize || 'LETTER'];
    this.PW = PW; this.PH = PH;
    this.left = 54; this.right = 54; this.top = 68; this.bottom = PH - 74;
    this.footerLines = opts.footerLines || FOOTER_LINES;
    this.doc = new PDFDocument({
      autoFirstPage: false, size: [PW, PH], margins: { top: 50, bottom: 50, left: this.left, right: this.right },
      compress: pass === 2, bufferPages: false, displayTitle: true,
      info: { Title: opts.title || 'Document', Subject: opts.subtitle || '', Author: opts.author || 'Generated document', Creator: 'tools/pdf engine', Keywords: opts.keywords || '' },
    });
    this.fontInfo = registerFonts(this.doc);
    this.x = this.left; this.w = PW - this.left - this.right; this.y = this.top;
    this.pageNo = 0; this.dry = false; this.breaks = []; this.hooks = [];
    this.chrome = { header: true }; this.section = ''; this.figNo = 0;
    this.base = { color: THEME.text, italic: false, size: 10 };
    this.headings = {}; this.slugs = new Set(); this.outStack = [{ level: 0, item: this.doc.outline }];
    this.warnings = []; this.stats = { code: 0, tables: 0, diagrams: 0, qa: 0 };
    this.doc.on('pageAdded', () => this.onPage());
  }

  get doc_() { return this.doc; }
  warn(msg) { if (!this.dry && this.warnings.length < 200) this.warnings.push(msg); }

  // ------------------------------------------------------------ pages & chrome
  onPage() {
    this.pageNo++; this.y = this.top; this.x = this.left; this.w = this.PW - this.left - this.right;
    this.drawChrome();
    for (const h of [...this.hooks]) h();
  }
  newPage(opts = {}) {
    if (this.dry) { this.breaks.push(this.y); this.y = this.top; return; }
    if (opts.header !== undefined) this.chrome.header = opts.header;
    const sx = this.x, sw = this.w;
    this.doc.addPage();
    this.x = sx; this.w = sw;
  }
  ensure(h) { if (this.y + h > this.bottom + 0.01) this.newPage(); }
  atTop() { return this.y <= this.top + 0.5; }
  space(h) { this.y += h; }

  fitStr(font, size, str, maxW) {
    let s = safe(this.doc, font, str);
    if (textWidth(this.doc, font, size, s) <= maxW) return s;
    while (s.length > 1 && textWidth(this.doc, font, size, s + '...') > maxW) s = s.slice(0, -1);
    return s + '...';
  }

  drawChrome() {
    const d = this.doc, W = this.PW - this.left - this.right, cx = this.left;
    d.save();
    if (this.chrome.header) {
      const hs = 8;
      const l = this.fitStr('foot', hs, this.opts.header ?? this.opts.title ?? '', W * 0.55);
      d.font('foot').fontSize(hs).fillColor(THEME.gray).text(l, cx, 34, { lineBreak: false });
      if (this.section) {
        const r = this.fitStr('foot-i', hs, this.section, W * 0.4);
        d.font('foot-i').fontSize(hs).fillColor(THEME.gray).text(r, cx + W - textWidth(d, 'foot-i', hs, r), 34, { lineBreak: false });
      }
      d.moveTo(cx, 48).lineTo(cx + W, 48).lineWidth(0.5).strokeColor(THEME.rule).stroke();
    }
    // mandatory two-line footer (small, gray) + page number
    const fy = this.PH - 62;
    d.moveTo(cx, fy).lineTo(cx + W, fy).lineWidth(0.5).strokeColor(THEME.rule).stroke();
    this.footerLines.forEach((line, i) => {
      let size = 7;
      const s = safe(d, 'foot', line);
      while (size > 4.5 && textWidth(d, 'foot', size, s) > W) size -= 0.25;
      const w = textWidth(d, 'foot', size, s);
      d.font('foot').fontSize(size).fillColor('#808080').text(s, cx + (W - w) / 2, fy + 6 + i * 9, { lineBreak: false });
    });
    const total = this.prev ? this.prev.pages : 0;
    const pn = `Page ${this.pageNo}${total ? ' of ' + total : ''}`;
    const pw = textWidth(d, 'foot', 8, pn);
    d.font('foot').fontSize(8).fillColor(THEME.gray).text(pn, cx + (W - pw) / 2, fy + 28, { lineBreak: false });
    d.restore();
  }

  // ------------------------------------------------------------ dry-run measuring & containers
  measure(fn) {
    const s = { y: this.y, dry: this.dry, breaks: this.breaks, x: this.x, w: this.w, figNo: this.figNo };
    this.dry = true; this.breaks = [];
    fn();
    const r = { startY: s.y, endY: this.y, breaks: this.breaks };
    this.y = s.y; this.dry = s.dry; this.breaks = s.breaks; this.x = s.x; this.w = s.w; this.figNo = s.figNo;
    return r;
  }

  container({ left = 0, right = 0, top = 0, bottom = 0, decor, minH = 40 }, fn) {
    this.ensure(minH);
    const sx = this.x, sw = this.w;
    const run = () => {
      this.y += top; this.x = sx + left; this.w = sw - left - right;
      fn();
      this.y += bottom; this.x = sx; this.w = sw;
    };
    if (this.dry) { run(); return; }
    const m = this.measure(run);
    const segs = []; let s0 = m.startY;
    for (const b of m.breaks) { segs.push([s0, Math.min(b + 2, this.PH)]); s0 = this.top; }
    segs.push([s0, m.endY]);
    const drawSeg = (k) => { const [a, b] = segs[k]; if (b - a > 0.5) { this.doc.save(); decor(this.doc, sx, sw, a, b, { first: k === 0, last: k === segs.length - 1 }); this.doc.restore(); } };
    let idx = 0;
    drawSeg(0);
    const hook = () => { idx++; if (idx < segs.length) drawSeg(idx); };
    this.hooks.push(hook);
    try { run(); } finally { this.hooks.splice(this.hooks.indexOf(hook), 1); }
  }

  // ------------------------------------------------------------ inline text layout
  layoutRuns(runs, width, o = {}) {
    const size = o.size || 10, doc = this.doc;
    const lines = [];
    let cur = { items: [], w: 0 };
    const pushLine = () => {
      while (cur.items.length && cur.items[cur.items.length - 1].space) cur.items.pop();
      const merged = [];
      for (const it of cur.items) {
        const p = merged[merged.length - 1];
        if (p && p.font === it.font && p.size === it.size && p.color === it.color && p.href === it.href && p.code === it.code) p.text += it.text;
        else merged.push({ ...it });
      }
      let tw = 0;
      for (const it of merged) { it.w = textWidth(doc, it.font, it.size, it.text); tw += it.w; }
      cur.items = merged; cur.w = tw;
      lines.push(cur); cur = { items: [], w: 0 };
    };
    for (const run of runs) {
      if (run.br) { pushLine(); continue; }
      const code = !!run.code;
      const b = run.b || o.bold, it = run.i || o.italic;
      const font = code ? (b ? 'mono-b' : 'mono') : 'sans' + (b && it ? '-bi' : b ? '-b' : it ? '-i' : '');
      const sz = code ? size * 0.9 : size;
      const color = run.href ? THEME.link : code ? THEME.codeInline : (o.color || THEME.text);
      const text = safe(doc, font, run.text);
      const style = { font, size: sz, color, href: run.href || null, code };
      for (const tok of text.split(/(\s+)/)) {
        if (tok === '') continue;
        if (/^\s+$/.test(tok)) {
          if (!cur.items.length || cur.items[cur.items.length - 1].space) continue;
          const w = textWidth(doc, font, sz, ' ');
          cur.items.push({ ...style, text: ' ', space: true, w }); cur.w += w; continue;
        }
        let word = tok;
        let ww = textWidth(doc, font, sz, word);
        if (cur.w + ww > width + 0.01 && cur.items.some((x) => !x.space)) pushLine();
        while (cur.w + ww > width + 0.01 && word.length > 1) {
          const avail = Math.max(width - cur.w, 0);
          let lo = 1, hi = word.length - 1;
          while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (textWidth(doc, font, sz, word.slice(0, mid)) <= avail) lo = mid; else hi = mid - 1; }
          let k = lo;
          if (textWidth(doc, font, sz, word.slice(0, k)) > avail && cur.items.some((x) => !x.space)) { pushLine(); continue; }
          const br = Math.max(word.slice(0, k).lastIndexOf('/'), word.slice(0, k).lastIndexOf('.'), word.slice(0, k).lastIndexOf('_'), word.slice(0, k).lastIndexOf('-'), word.slice(0, k).lastIndexOf(':'), word.slice(0, k).lastIndexOf(','));
          if (br >= k * 0.5 && br + 1 < word.length) k = br + 1;
          const part = word.slice(0, k);
          cur.items.push({ ...style, text: part, w: textWidth(doc, font, sz, part) });
          cur.w += cur.items[cur.items.length - 1].w;
          pushLine();
          word = word.slice(k); ww = textWidth(doc, font, sz, word);
        }
        if (word) { cur.items.push({ ...style, text: word, w: ww }); cur.w += ww; }
      }
    }
    if (cur.items.length) pushLine();
    return lines;
  }

  drawLine(line, x, y, o = {}) {
    if (this.dry) return;
    const d = this.doc, size = o.size || 10, lh = o.lh || size * 1.42;
    let xx = x;
    if (o.align === 'center') xx = x + ((o.width || this.w) - line.w) / 2;
    else if (o.align === 'right') xx = x + (o.width || this.w) - line.w;
    const baseAsc = ascent(d, 'sans', size);
    for (const it of line.items) {
      const dy = baseAsc - ascent(d, it.font, it.size);
      if (it.code) d.save().roundedRect(xx - 0.5, y + lh * 0.1, it.w + 1, lh * 0.8, 2).fill(THEME.codeInlineBg).restore();
      d.font(it.font).fontSize(it.size).fillColor(it.color).text(it.text, xx, y + dy, { lineBreak: false });
      if (it.href) {
        d.save().moveTo(xx, y + baseAsc + 1.5).lineTo(xx + it.w, y + baseAsc + 1.5).lineWidth(0.5).strokeColor(it.color).stroke().restore();
        if (it.href.startsWith('#')) d.goTo(xx, y, it.w, lh, it.href.slice(1));
        else if (/^(https?:|mailto:)/i.test(it.href)) d.link(xx, y, it.w, lh, it.href);
      }
      xx += it.w;
    }
  }

  /** Draw lines top-down with page-break handling. */
  flow(lines, o = {}) {
    const size = o.size || 10, lh = o.lh || size * 1.42, x = o.x ?? this.x, n = lines.length;
    for (let k = 0; k < n; k++) {
      this.ensure(k === 0 ? lh * Math.min(n, 2) : lh);
      this.drawLine(lines[k], x, this.y, { ...o, size, lh, width: o.width ?? this.w });
      this.y += lh;
    }
  }

  para(text, o = {}) {
    const size = o.size || this.base.size;
    const ind = o.indent || 0;
    const runs = parseInline(text);
    const lines = this.layoutRuns(runs, this.w - ind, { size, color: o.color || this.base.color, bold: o.bold, italic: o.italic ?? this.base.italic });
    if (o.keep) this.ensure(lines.length * (o.lh || size * 1.42) + o.keep);
    this.flow(lines, { size, x: this.x + ind, width: this.w - ind, align: o.align, lh: o.lh });
    this.y += o.after ?? 6;
  }

  // ------------------------------------------------------------ headings, TOC, title page
  heading(b, next) {
    const sizes = { 1: 22, 2: 16.5, 3: 13, 4: 11 };
    const size = sizes[b.level];
    const before = { 1: 0, 2: 14, 3: 10, 4: 8 }[b.level];
    if (b.level === 1) {
      this.section = plainText(b.text);
      if (!this.atTop() || this.pageNo === 0) this.newPage();
    } else if (!this.atTop()) this.y += before;
    const color = b.level === 4 ? '#3B4B5E' : THEME.navy;
    const runs = parseInline(b.text);
    const lines = this.layoutRuns(runs, this.w, { size, bold: true, color });
    const lh = size * 1.3;
    this.ensure(lh * lines.length + (b.level === 1 ? 20 : Math.max(46, this.peekNeed(next))));
    if (!this.dry && b.hid !== undefined) this.registerHeading(b);
    this.flow(lines, { size, lh });
    if (b.level === 1) {
      if (!this.dry) this.doc.save().moveTo(this.x, this.y + 1).lineTo(this.x + this.w, this.y + 1).lineWidth(1.5).strokeColor(THEME.accent).stroke().restore();
      this.y += 12;
    } else this.y += b.level === 2 ? 8 : 4;
  }

  registerHeading(b) {
    const d = this.doc;
    this.headings[b.hid] = this.pageNo;
    d.addNamedDestination('h' + b.hid, 'XYZ', this.left, this.y - 4, null);
    let slug = slugify(b.text) || 'section', s = slug, n = 1;
    while (this.slugs.has(s)) s = slug + '-' + n++;
    this.slugs.add(s);
    d.addNamedDestination(s, 'XYZ', this.left, this.y - 4, null);
    while (this.outStack.length > 1 && this.outStack[this.outStack.length - 1].level >= b.level) this.outStack.pop();
    const item = this.outStack[this.outStack.length - 1].item.addItem(safe(d, 'sans', plainText(b.text)), { expanded: b.level < 2 });
    this.outStack.push({ level: b.level, item });
  }

  outlineEntry(title, level) { // for non-TOC entries (e.g. interview questions): attach under current section
    while (this.outStack.length > 1 && this.outStack[this.outStack.length - 1].level >= level) this.outStack.pop();
    const item = this.outStack[this.outStack.length - 1].item.addItem(safe(this.doc, 'sans', title), { expanded: false });
    this.outStack.push({ level, item });
  }

  titlePage() {
    const o = this.opts, d = this.doc;
    this.chrome.header = false; this.newPage();
    d.rect(0, 0, this.PW, 330).fill(THEME.navy);
    d.rect(0, 330, this.PW, 5).fill(THEME.accent);
    const W = this.PW - 2 * this.left - 20;
    let y = 110;
    const tl = this.layoutRuns(parseInline(o.title || 'Untitled'), W, { size: 32, bold: true, color: '#FFFFFF' });
    for (const l of tl) { this.drawLine(l, this.left, y, { size: 32, lh: 40, width: W }); y += 40; }
    y += 10;
    if (o.subtitle) {
      const sl = this.layoutRuns(parseInline(o.subtitle), W, { size: 15, color: '#D4E6F6' });
      for (const l of sl) { this.drawLine(l, this.left, y, { size: 15, lh: 21, width: W }); y += 21; }
    }
    y = 370;
    const meta = [['Version', o.version], ['Date', o.date || new Date().toISOString().slice(0, 10)], ['Author', o.author]].filter((m) => m[1]);
    for (const [k, v] of meta) {
      d.font('sans-b').fontSize(10).fillColor(THEME.gray).text(k, this.left, y, { lineBreak: false });
      d.font('sans').fontSize(10).fillColor(THEME.text).text(safe(d, 'sans', v), this.left + 70, y, { lineBreak: false });
      y += 18;
    }
    const disc = o.disclaimer ?? 'Disclaimer: This document describes an illustrative design that uses synthetic data only. Behaviour that was not executed is labelled as such.';
    if (disc) {
      const dl = this.layoutRuns(parseInline(disc), this.w - 24, { size: 8.5, color: '#4A5561' });
      const h = dl.length * 12 + 18, by = this.bottom - h - 10;
      d.save().roundedRect(this.left, by, this.w, h, 4).fillAndStroke('#F5F7FA', THEME.rule).restore();
      dl.forEach((l, i) => this.drawLine(l, this.left + 12, by + 9 + i * 12, { size: 8.5, lh: 12, width: this.w - 24 }));
    }
    this.y = this.bottom + 1; // force a new page next
  }

  toc(entries) {
    const d = this.doc, depth = this.opts.tocDepth ?? 3;
    this.chrome.header = true; this.section = '';
    this.newPage();
    this.outStack = [{ level: 0, item: this.doc.outline }];
    d.outline.addItem('Contents');
    d.addNamedDestination('toc', 'XYZ', this.left, this.top, null);
    d.font('sans-b').fontSize(22).fillColor(THEME.navy).text('Contents', this.left, this.y, { lineBreak: false });
    this.y += 34;
    d.save().moveTo(this.left, this.y - 6).lineTo(this.left + this.w, this.y - 6).lineWidth(1.5).strokeColor(THEME.accent).stroke().restore();
    for (const e of entries) {
      if (e.level > depth) continue;
      const size = e.level === 1 ? 10.5 : e.level === 2 ? 10 : 9;
      const ind = (e.level - 1) * 14, numW = 28;
      const lh = size * 1.45;
      const lines = this.layoutRuns(parseInline(e.text), this.w - ind - numW - 6, { size, bold: e.level === 1, color: e.level === 1 ? THEME.navy : THEME.text });
      const gapBefore = e.level === 1 ? 5 : 0;
      this.ensure(lh * lines.length + gapBefore);
      this.y += gapBefore;
      const page = this.prev ? this.prev.headings[e.id] ?? 0 : 0;
      lines.forEach((l, i) => {
        this.drawLine(l, this.x + ind, this.y, { size, lh, width: this.w });
        if (i === lines.length - 1) {
          const ns = String(page);
          const nw = textWidth(d, 'sans', size, ns);
          d.font('sans').fontSize(size).fillColor(THEME.text).text(ns, this.x + this.w - nw, this.y + (ascent(d, 'sans', size) * 0 ), { lineBreak: false });
          const lx = this.x + ind + l.w + 5, rx = this.x + this.w - nw - 5;
          if (rx - lx > 8) {
            const by = this.y + ascent(d, 'sans', size) + 0.5;
            d.save().lineWidth(0.6).strokeColor('#A0A9B3').dash(0.6, { space: 2.4 }).moveTo(lx, by).lineTo(rx, by).stroke().undash().restore();
          }
        }
        this.y += lh;
      });
      d.goTo(this.x + ind, this.y - lh * lines.length, this.w - ind, lh * lines.length, 'h' + e.id);
    }
  }

  // ------------------------------------------------------------ block renderers
  blocks(list) { for (let i = 0; i < list.length; i++) this.block(list[i], list[i + 1]); }
  md(text, opts = {}) { this.blocks(parseMarkdown(text, opts)); }

  /** Height the following block wants to keep together with a heading / lead-in paragraph. */
  peekNeed(next) {
    if (!next) return 46;
    const pageH = this.bottom - this.top;
    switch (next.t) {
      case 'diagram': {
        if (!next.spec) return 60;
        try {
          const sc = buildScene(this.doc, next.spec, this.w);
          const s = Math.min(1, this.w / sc.w);
          const cap = next.spec.caption || next.caption ? 30 : 8;
          return Math.min(sc.cuts ? 220 : sc.h * s + cap, pageH - 60);
        } catch { return 60; }
      }
      case 'table': case 'code': return 80;
      case 'figure': return 120;
      case 'callout': case 'quote': return 70;
      case 'qa': return 140;
      default: return 46;
    }
  }

  block(b, next) {
    switch (b.t) {
      case 'h': return this.heading(b, next);
      case 'p': return this.para(b.text, /:\s*$/.test(b.text) && next ? { keep: Math.min(this.peekNeed(next), 120) } : {});
      case 'list': this.list(b, 0); this.y += 4; return;
      case 'code': return this.code(b.text, b.lang);
      case 'table': return this.table(b);
      case 'callout': return this.callout(b.kind, b.title, b.blocks);
      case 'quote': return this.quote(b.blocks);
      case 'hr': this.y += 6; this.ensure(10); if (!this.dry) this.doc.save().moveTo(this.x, this.y).lineTo(this.x + this.w, this.y).lineWidth(0.8).strokeColor(THEME.rule).stroke().restore(); this.y += 10; return;
      case 'pagebreak': if (!this.atTop()) this.newPage(); return;
      case 'figure': return this.figure(b);
      case 'diagram': return this.diagram(b);
      case 'qa': return renderQuestion(this, b.q);
      default: this.warn('unknown block ' + b.t);
    }
  }

  list(l, level) {
    const d = this.doc, size = this.base.size;
    const lh = size * 1.42;
    let n = l.start || 1;
    const markerW = l.ordered ? Math.max(18, textWidth(d, 'sans', size, String(n + l.items.length) + '.') + 6) : 14;
    for (const item of l.items) {
      const runs = parseInline(item.text);
      const tw = this.w - markerW - (item.task !== null && item.task !== undefined ? 14 : 0);
      const xoff = markerW + (item.task !== null && item.task !== undefined ? 14 : 0);
      const lines = this.layoutRuns(runs, tw, { size, color: this.base.color, italic: this.base.italic });
      this.ensure(lh * Math.min(Math.max(lines.length, 1), 2));
      const y0 = this.y;
      if (!this.dry) {
        d.save();
        if (l.ordered) {
          const s = n + '.';
          d.font('sans').fontSize(size).fillColor(THEME.gray).text(s, this.x + markerW - 4 - textWidth(d, 'sans', size, s), y0, { lineBreak: false });
        } else {
          const cy = y0 + ascent(d, 'sans', size) * 0.62, cx = this.x + 5;
          if (level === 0) d.circle(cx, cy, 1.9).fill(THEME.navy);
          else if (level === 1) d.circle(cx, cy, 1.7).lineWidth(0.8).stroke(THEME.navy);
          else d.rect(cx - 1.5, cy - 1.5, 3, 3).fill(THEME.gray);
        }
        if (item.task !== null && item.task !== undefined) {
          const bx = this.x + markerW, by = y0 + 1.5;
          d.lineWidth(0.8).strokeColor(THEME.gray).rect(bx, by, 9, 9).stroke();
          if (item.task) d.lineWidth(1.4).strokeColor('#2E9D57').moveTo(bx + 2, by + 4.8).lineTo(bx + 4, by + 7).lineTo(bx + 7.5, by + 2).stroke();
        }
        d.restore();
      }
      this.flow(lines, { size, lh, x: this.x + xoff, width: tw });
      this.y += 2;
      if (item.extra.length || item.sub.length) {
        const sx = this.x, sw = this.w;
        this.x += markerW; this.w -= markerW;
        for (const e of item.extra) this.block(e);
        for (const s of item.sub) this.list(s, level + 1);
        this.x = sx; this.w = sw;
      }
      n++;
    }
  }

  code(text, lang) {
    const d = this.doc, size = 8, lh = 11.4, pad = 6;
    this.stats.code++;
    const maxW = this.w - 2 * pad;
    const cw = textWidth(d, 'mono', size, 'MMMMMMMMMM') / 10;
    const cols = Math.max(10, Math.floor(maxW / cw));
    const out = [];
    for (const raw of String(text).replace(/\r/g, '').split('\n')) {
      let line = safe(d, 'mono', raw).replace(/\s+$/, '');
      if (line.length <= cols) { out.push(line); continue; }
      const lead = (/^ */.exec(line)[0] || '').length;
      const ind = ' '.repeat(Math.min(lead + 2, Math.floor(cols / 3)));
      let first = true;
      while (line.length > (first ? cols : cols - ind.length)) {
        const lim = first ? cols : cols - ind.length;
        let k = lim;
        const sp = line.lastIndexOf(' ', lim);
        if (sp > lim * 0.6) k = sp + 1;
        else { const m = Math.max(line.lastIndexOf(',', lim), line.lastIndexOf('/', lim), line.lastIndexOf('.', lim), line.lastIndexOf('(', lim)); if (m > lim * 0.7) k = m + 1; }
        out.push((first ? '' : ind) + line.slice(0, k).replace(/\s+$/, ''));
        line = line.slice(k); first = false;
      }
      out.push(ind + line);
    }
    if (!out.length) out.push('');
    let i = 0, firstSeg = true;
    const label = lang && lang !== 'text' ? lang : '';
    this.ensure(pad * 2 + lh * Math.min(out.length, 3) + (label ? 12 : 0));
    while (i < out.length) {
      const strip = firstSeg && label ? 12 : 0;
      let avail = this.bottom - this.y - 2 * pad - strip;
      if (avail < lh * 2 && out.length - i > 1) { this.newPage(); continue; }
      const nl = Math.max(1, Math.min(out.length - i, Math.floor(avail / lh)));
      const h = nl * lh + 2 * pad + strip;
      if (!this.dry) {
        d.save();
        d.roundedRect(this.x, this.y, this.w, h, 3).fillAndStroke(THEME.codeBg, THEME.codeBorder);
        if (strip) {
          d.save().rect(this.x + 0.5, this.y + 0.5, this.w - 1, strip - 0.5).fill('#E6EAEE').restore();
          d.font('sans-b').fontSize(6.5).fillColor(THEME.gray).text(safe(d, 'sans-b', label.toUpperCase()), this.x + pad, this.y + 3, { lineBreak: false });
        }
        d.font('mono').fontSize(size).fillColor(THEME.codeText);
        for (let k = 0; k < nl; k++) {
          const s = out[i + k];
          if (s) d.text(s, this.x + pad, this.y + strip + pad + k * lh + 1, { lineBreak: false });
        }
        d.restore();
      }
      this.y += h; i += nl; firstSeg = false;
      if (i < out.length) this.newPage();
    }
    this.y += 8;
  }

  table(t) {
    const d = this.doc, W = this.w;
    this.stats.tables++;
    const ncols = Math.max(t.header.length, ...t.rows.map((r) => r.length));
    const cellRuns = (txt, head) => parseInline(txt || '', head ? { b: true } : {});
    const hdr = Array.from({ length: ncols }, (_, c) => cellRuns(t.header[c], true));
    const body = t.rows.map((r) => Array.from({ length: ncols }, (_, c) => cellRuns(r[c], false)));
    const padX = 5, padY = 3.5;
    let size = 9, minW = [], natW = [];
    const measureAt = (sz) => {
      minW = new Array(ncols).fill(0); natW = new Array(ncols).fill(0);
      const all = [hdr, ...body];
      for (const row of all) {
        row.forEach((runs, c) => {
          const lines = this.layoutRuns(runs, 1e6, { size: sz, bold: row === hdr });
          const nat = lines.reduce((m, l) => Math.max(m, l.w), 0);
          let mw = 0;
          for (const r of runs) {
            if (r.br || !r.text) continue;
            const font = r.code ? 'mono' : (r.b || row === hdr) ? 'sans-b' : 'sans';
            const s = r.code ? sz * 0.9 : sz;
            for (const w of safe(d, font, r.text).split(/\s+/)) if (w) mw = Math.max(mw, textWidth(d, font, s, w));
          }
          minW[c] = Math.max(minW[c], Math.min(mw, 140)); natW[c] = Math.max(natW[c], nat);
        });
      }
      minW = minW.map((x) => x + 2 * padX); natW = natW.map((x) => x + 2 * padX);
    };
    const sizes = [9, 8.5, 8, 7.5, 7, 6.5, 6];
    for (const sz of sizes) {
      size = sz; measureAt(sz);
      if (minW.reduce((a, b) => a + b, 0) <= W) break;
    }
    natW = natW.map((n, c) => Math.max(n, minW[c]));
    const sumMin = minW.reduce((a, b) => a + b, 0), sumNat = natW.reduce((a, b) => a + b, 0);
    let widths;
    if (sumMin > W) widths = minW.map((m) => (m / sumMin) * W);
    else if (sumNat <= W) widths = natW.map((n) => n + ((W - sumNat) * n) / sumNat);
    else { const extra = W - sumMin, want = sumNat - sumMin; widths = minW.map((m, c) => m + (extra * (natW[c] - m)) / want); }
    const lh = size * 1.32;
    const layoutRow = (row, head) => {
      const cells = row.map((runs, c) => this.layoutRuns(runs, widths[c] - 2 * padX, { size, bold: head, color: head ? '#FFFFFF' : THEME.text }));
      const nl = Math.max(1, ...cells.map((c) => c.length));
      return { cells, nl };
    };
    const H = layoutRow(hdr, true);
    const hh = H.nl * lh + 2 * padY;
    const xs = []; let acc = this.x; for (const w of widths) { xs.push(acc); acc += w; }
    const drawSlice = (R, from, count, head, zebra, topBorder) => {
      const h = count * lh + 2 * padY;
      if (!this.dry) {
        d.save();
        if (head) d.rect(this.x, this.y, W, h).fill(THEME.tableHead);
        else if (zebra) d.rect(this.x, this.y, W, h).fill(THEME.zebra);
        d.lineWidth(0.5).strokeColor(head ? '#3C5A82' : THEME.tableLine);
        d.moveTo(this.x, this.y + h).lineTo(this.x + W, this.y + h).stroke();
        if (topBorder) d.moveTo(this.x, this.y).lineTo(this.x + W, this.y).stroke();
        for (let c = 0; c <= ncols; c++) { const vx = c === ncols ? this.x + W : xs[c]; d.moveTo(vx, this.y).lineTo(vx, this.y + h).stroke(); }
        d.restore();
        R.cells.forEach((lines, c) => {
          for (let k = 0; k < count; k++) {
            const l = lines[from + k]; if (!l) continue;
            this.drawLine(l, xs[c] + padX, this.y + padY + k * lh, { size, lh, align: t.aligns[c] || 'left', width: widths[c] - 2 * padX });
          }
        });
      }
      this.y += h;
    };
    const drawHeader = () => drawSlice(H, 0, H.nl, true, false, true);
    const firstRow = body.length ? layoutRow(body[0], false) : null;
    this.ensure(hh + (firstRow ? Math.min(firstRow.nl, 3) * lh + 2 * padY : 0));
    drawHeader();
    const pageCap = this.bottom - this.top - hh;
    body.forEach((row, ri) => {
      const R = ri === 0 ? firstRow : layoutRow(row, false);
      const rh = R.nl * lh + 2 * padY;
      if (this.y + rh <= this.bottom + 0.01) { drawSlice(R, 0, R.nl, false, ri % 2 === 1, false); return; }
      if (rh <= pageCap) { this.newPage(); drawHeader(); drawSlice(R, 0, R.nl, false, ri % 2 === 1, false); return; }
      // very tall row: split by lines
      let done = 0;
      while (done < R.nl) {
        let fit = Math.floor((this.bottom - this.y - 2 * padY) / lh);
        if (fit < 2 && R.nl - done > 1) { this.newPage(); drawHeader(); continue; }
        fit = Math.max(1, Math.min(fit, R.nl - done));
        drawSlice(R, done, fit, false, ri % 2 === 1, false);
        done += fit;
        if (done < R.nl) { this.newPage(); drawHeader(); }
      }
    });
    this.y += 10;
  }

  callout(kind, title, blocks) {
    const st = THEME.callouts[kind] || THEME.callouts.note;
    this.y += 2;
    this.container({
      left: 14, right: 10, top: 7, bottom: 2,
      decor: (d, x, w, a, b) => {
        d.roundedRect(x, a, w, b - a, 3).fillAndStroke(st.fill, st.fill);
        d.rect(x, a, 3.5, b - a).fill(st.bar);
      },
    }, () => {
      this.para(title || st.label, { bold: true, color: st.title, after: 3, size: 9.5 });
      const saved = this.base; this.base = { ...saved, size: 9.5 };
      this.blocks(blocks);
      this.base = saved;
    });
    this.y += 8;
  }

  quote(blocks) {
    this.y += 2;
    this.container({
      left: 14, right: 4, top: 2, bottom: 0,
      decor: (d, x, w, a, b) => { d.rect(x + 2, a, 2.5, b - a).fill('#B8C2CC'); },
    }, () => {
      const saved = this.base; this.base = { ...saved, color: '#4A5561', italic: true };
      this.blocks(blocks);
      this.base = saved;
    });
    this.y += 6;
  }

  // ------------------------------------------------------------ figures & captions
  captionLines(text) {
    const label = `Figure ${this.figNo + 1}. ${text}`;
    return this.layoutRuns(parseInline(label), this.w - 40, { size: 8.5, italic: true, color: THEME.gray });
  }
  caption(text) {
    if (!text) return;
    const lines = this.captionLines(text);
    this.flow(lines, { size: 8.5, lh: 12, x: this.x + 20, width: this.w - 40, align: 'center' });
    if (!this.dry) this.figNo++;
    this.y += 10;
  }

  figure(b) {
    const d = this.doc;
    const file = path.resolve(b.baseDir || process.cwd(), b.src);
    let img = null;
    if (/\.(png|jpe?g)$/i.test(file) && fs.existsSync(file)) { try { img = d.openImage(file); } catch { img = null; } }
    if (!img) {
      this.warn('figure not found: ' + b.src);
      this.ensure(60);
      if (!this.dry) { d.save().roundedRect(this.x, this.y, this.w, 50, 3).dash(3, { space: 3 }).stroke(THEME.light).undash().restore(); d.font('sans-i').fontSize(9).fillColor(THEME.gray).text(safe(d, 'sans-i', `[image not available: ${b.src}]`), this.x + 10, this.y + 20, { lineBreak: false }); }
      this.y += 58; this.caption(b.caption); return;
    }
    const maxH = 380; let sc = Math.min(1, this.w / img.width, maxH / img.height);
    const w = img.width * sc, h = img.height * sc;
    this.ensure(h + 30);
    if (!this.dry) d.image(img, this.x + (this.w - w) / 2, this.y, { width: w, height: h });
    this.y += h + 6; this.caption(b.caption);
  }

  diagram(b) {
    this.stats.diagrams++;
    if (!b.spec) { this.warn('diagram error: ' + b.error); return this.callout('warning', 'Diagram could not be rendered', [{ t: 'p', text: String(b.error || 'invalid spec') }]); }
    const spec = b.caption && !b.spec.caption ? { ...b.spec, caption: b.caption } : b.spec;
    try { renderDiagram(this, spec); } catch (e) { this.warn('diagram failed: ' + e.message); throw e; }
  }

  // ------------------------------------------------------------ main run
  run(units, tocEntries) {
    this.titlePage();
    if (this.opts.toc !== false && tocEntries.length) this.toc(tocEntries);
    this.chrome.header = true; this.section = '';
    for (const u of units) this.blocks(u.blocks);
    this.doc.end();
  }
}

// ---------------------------------------------------------------- orchestration
function prepare(opts) {
  const units = []; const entries = []; let hid = 0;
  const addUnit = (blocks) => {
    for (const b of blocks) if (b.t === 'h') { b.hid = hid++; entries.push({ id: b.hid, level: b.level, text: b.text }); }
    units.push({ blocks });
  };
  for (const part of opts.parts || []) {
    let blocks = [];
    const baseDir = part.baseDir || opts.baseDir || (part.file ? path.dirname(path.resolve(part.file)) : process.cwd());
    if (part.title) blocks.push({ t: 'h', level: part.level || 1, text: part.title });
    switch (part.type) {
      case 'markdown': {
        const text = part.text ?? readMarkdownFile(part.file);
        blocks.push(...parseMarkdown(text, { baseDir }));
        break;
      }
      case 'interview': {
        const files = part.files || (part.file ? [part.file] : []);
        const texts = part.texts || files.map(readMarkdownFile);
        for (const t of texts) blocks.push(...interviewToBlocks(parseInterview(t), { baseDir }));
        break;
      }
      case 'diagram':
        blocks.push({ t: 'diagram', spec: part.spec, caption: part.caption });
        break;
      case 'pagebreak': blocks.push({ t: 'pagebreak' }); break;
      default: throw new Error('Unknown part type: ' + part.type);
    }
    addUnit(blocks);
  }
  return { units, entries };
}

function runPass(opts, prep, pass, prev) {
  return new Promise((resolve, reject) => {
    const r = new Renderer(opts, pass, prev);
    let out;
    if (pass === 1) out = nullStream();
    else { fs.mkdirSync(path.dirname(path.resolve(opts.outFile)), { recursive: true }); out = fs.createWriteStream(opts.outFile); }
    out.on('error', reject);
    out.on('finish', () => resolve({ pages: r.pageNo, headings: r.headings, warnings: r.warnings, fonts: r.fontInfo, stats: r.stats }));
    r.doc.on('error', reject);
    r.doc.pipe(out);
    try { r.run(prep.units, prep.entries); } catch (e) { reject(e); }
  });
}

/**
 * buildDocument({title, subtitle, version, date, author, disclaimer, outFile, toc, tocDepth, pageSize, header, footerLines,
 *   baseDir, parts:[{type:'markdown', file|text, title?}, {type:'interview', files:[...]}, {type:'diagram', spec, caption?}, {type:'pagebreak'}]})
 * -> {outFile, pages, ms, warnings, fonts, stats}
 */
export async function buildDocument(opts) {
  if (!opts.outFile) throw new Error('outFile is required');
  const t0 = Date.now();
  const prep = prepare(opts);
  const p1 = await runPass(opts, prep, 1, null);
  const p2 = await runPass(opts, prep, 2, p1);
  const warnings = [...p2.warnings];
  if (p2.pages !== p1.pages) warnings.push(`page count changed between passes (${p1.pages} -> ${p2.pages}); TOC page numbers may be off`);
  return { outFile: opts.outFile, pages: p2.pages, ms: Date.now() - t0, warnings, fonts: p2.fonts, stats: p2.stats };
}
