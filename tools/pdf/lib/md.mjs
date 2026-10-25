// Markdown-subset parser: text -> block list, plus directory loading helpers.
import fs from 'node:fs';
import path from 'node:path';

// ---------------------------------------------------------------- inline
/** Parse inline markdown into runs: {text, b, i, code, href, br}. */
export function parseInline(src, base = {}) {
  const runs = [];
  const st = { b: !!base.b, i: !!base.i, href: base.href || null };
  let buf = '';
  const flush = () => {
    if (buf) { runs.push({ text: buf, b: st.b, i: st.i, href: st.href }); buf = ''; }
  };
  const s = String(src ?? '');
  const n = s.length;
  const isWord = (c) => c !== undefined && /[\p{L}\p{N}]/u.test(c);
  let i = 0;
  while (i < n) {
    const c = s[i];
    if (c === '\\' && i + 1 < n && /[\\`*_{}\[\]()#+\-.!|<>~]/.test(s[i + 1])) { buf += s[i + 1]; i += 2; continue; }
    if (c === '\n') { flush(); runs.push({ br: true }); i++; continue; }
    if (c === '`') {
      let k = i; while (s[k] === '`') k++;
      const fence = s.slice(i, k);
      const end = s.indexOf(fence, k);
      if (end > k) {
        flush();
        let code = s.slice(k, end).replace(/\n/g, ' ');
        if (code.length > 2 && code.startsWith(' ') && code.endsWith(' ')) code = code.slice(1, -1);
        runs.push({ text: code, code: true, b: st.b, i: st.i, href: st.href });
        i = end + fence.length; continue;
      }
      buf += fence; i = k; continue;
    }
    if (c === '<') {
      const m = /^<(https?:\/\/[^>\s]+)>/.exec(s.slice(i));
      if (m) { flush(); runs.push({ text: m[1], href: m[1], b: st.b, i: st.i }); i += m[0].length; continue; }
      const br = /^<br\s*\/?>/i.exec(s.slice(i));
      if (br) { flush(); runs.push({ br: true }); i += br[0].length; continue; }
    }
    if (c === '[') {
      // [text](url)
      let depth = 0, k = i;
      for (; k < n; k++) { if (s[k] === '[') depth++; else if (s[k] === ']') { depth--; if (depth === 0) break; } }
      if (k < n && s[k + 1] === '(') {
        let j = k + 2, pd = 1;
        for (; j < n; j++) { if (s[j] === '(') pd++; else if (s[j] === ')') { pd--; if (pd === 0) break; } }
        if (j < n) {
          let url = s.slice(k + 2, j).trim().replace(/\s+".*"$/, '');
          flush();
          const inner = parseInline(s.slice(i + 1, k), { ...st, href: url });
          runs.push(...inner);
          i = j + 1; continue;
        }
      }
    }
    if (c === '*' || c === '_') {
      let k = i; while (s[k] === c) k++;
      const len = Math.min(k - i, 3);
      const prev = s[i - 1], next = s[k];
      let ok = true;
      if (c === '_') ok = !(isWord(prev) && isWord(next)) && !(isWord(prev) && !st.i && !st.b);
      if (ok && len <= 3) {
        const closing = (len === 3 ? (st.b && st.i) : len === 2 ? st.b : st.i);
        const canClose = closing && prev !== undefined && !/\s/.test(prev);
        const canOpen = !closing && next !== undefined && !/\s/.test(next) && s.indexOf(c.repeat(len), k) !== -1;
        if (canClose || canOpen) {
          flush();
          if (len >= 2) st.b = !st.b;
          if (len === 1 || len === 3) st.i = !st.i;
          i += len; if (k - i > 0) { /* extra markers literal */ }
          continue;
        }
      }
      buf += s.slice(i, k); i = k; continue;
    }
    if (c === 'h' && /^https?:\/\//.test(s.slice(i, i + 8)) && !isWord(s[i - 1])) {
      const m = /^https?:\/\/[^\s<>)\]]+/.exec(s.slice(i));
      if (m) {
        let url = m[0].replace(/[.,;:!?'"]+$/, '');
        flush();
        runs.push({ text: url, href: st.href || url, b: st.b, i: st.i });
        i += url.length; continue;
      }
    }
    buf += c; i++;
  }
  flush();
  return runs;
}

export function plainText(src) {
  return parseInline(src).map((r) => (r.br ? ' ' : r.text)).join('');
}

export function slugify(text) {
  return plainText(text).toLowerCase().replace(/[^\p{L}\p{N}\s-]/gu, '').trim().replace(/\s+/g, '-');
}

// ---------------------------------------------------------------- blocks
const FENCE_RE = /^(\s*)(`{3,}|~{3,})\s*(.*)$/;
const LIST_RE = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;
const HR_RE = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const HEAD_RE = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
const TABLE_SEP_RE = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;

function indentOf(line) {
  let n = 0;
  for (const ch of line) { if (ch === ' ') n++; else if (ch === '\t') n += 4; else break; }
  return n;
}

export function splitRow(line) {
  let s = line.trim();
  if (s.startsWith('|')) s = s.slice(1);
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1);
  const cells = [];
  let cur = '';
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\' && s[i + 1] === '|') { cur += '|'; i++; } else if (s[i] === '|') { cells.push(cur.trim()); cur = ''; } else cur += s[i];
  }
  cells.push(cur.trim());
  return cells;
}

function isBlockStart(line, next) {
  return FENCE_RE.test(line) || HEAD_RE.test(line) || HR_RE.test(line) || /^\s*>/.test(line) || LIST_RE.test(line) ||
    /^\s*:::/.test(line) || /^\s*!\[.*\]\(.*\)\s*$/.test(line) || /^\s*<!--/.test(line) || /^\s*\\pagebreak\s*$/.test(line) ||
    (line.includes('|') && next !== undefined && TABLE_SEP_RE.test(next) && next.includes('-'));
}

/** Parse a markdown subset into blocks. opts.baseDir resolves relative diagram/image files. */
export function parseMarkdown(text, opts = {}) {
  const lines = String(text ?? '').replace(/\r\n?/g, '\n').split('\n');
  return parseLines(lines, opts);
}

function parseLines(lines, opts) {
  const blocks = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }

    if (/^\s*<!--/.test(line)) {
      const buf = [];
      while (i < lines.length) { buf.push(lines[i]); if (lines[i].includes('-->')) { i++; break; } i++; }
      if (/pagebreak/i.test(buf.join(' '))) blocks.push({ t: 'pagebreak' });
      continue;
    }
    if (/^\s*\\pagebreak\s*$/.test(line)) { blocks.push({ t: 'pagebreak' }); i++; continue; }

    let m = FENCE_RE.exec(line);
    if (m) {
      const [, , fence, info] = m;
      const body = [];
      i++;
      const closeRe = new RegExp('^\\s*' + fence[0] + '{' + fence.length + ',}\\s*$');
      while (i < lines.length && !closeRe.test(lines[i])) { body.push(lines[i]); i++; }
      i++;
      const indent = m[1].length;
      const text2 = body.map((l) => (indent ? l.replace(new RegExp('^\\s{0,' + indent + '}'), '') : l)).join('\n');
      const lang = info.trim();
      if (/^diagram\b/i.test(lang)) blocks.push({ t: 'diagram', ...resolveDiagram(lang, text2, opts) });
      else blocks.push({ t: 'code', lang: lang.split(/\s+/)[0] || '', text: text2 });
      continue;
    }

    m = HEAD_RE.exec(line);
    if (m) { blocks.push({ t: 'h', level: Math.min(m[1].length, 4), text: m[2] }); i++; continue; }
    if (HR_RE.test(line)) { blocks.push({ t: 'hr' }); i++; continue; }

    // :::note ... :::
    m = /^\s*:::\s*(\w+)\s*(.*)$/.exec(line);
    if (m) {
      const kind = m[1].toLowerCase(); const title = m[2].trim();
      const body = []; i++;
      let depth = 1;
      while (i < lines.length) {
        if (/^\s*:::\s*\w+/.test(lines[i])) depth++;
        else if (/^\s*:::\s*$/.test(lines[i])) { depth--; if (depth === 0) break; }
        body.push(lines[i]); i++;
      }
      i++;
      blocks.push({ t: 'callout', kind: normKind(kind), title: title || null, blocks: parseLines(body, opts) });
      continue;
    }

    if (/^\s*>/.test(line)) {
      const body = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) { body.push(lines[i].replace(/^\s*>\s?/, '')); i++; }
      const cm = /^\s*\[!(NOTE|WARNING|TIP|IMPORTANT|CAUTION|INFO)\]\s*(.*)$/i.exec(body[0] || '');
      if (cm) {
        blocks.push({ t: 'callout', kind: normKind(cm[1].toLowerCase()), title: cm[2].trim() || null, blocks: parseLines(body.slice(1), opts) });
      } else blocks.push({ t: 'quote', blocks: parseLines(body, opts) });
      continue;
    }

    m = /^\s*!\[(.*)\]\((.*?)\)\s*$/.exec(line);
    if (m) { blocks.push({ t: 'figure', caption: m[1], src: m[2], baseDir: opts.baseDir }); i++; continue; }

    if (line.includes('|') && i + 1 < lines.length && TABLE_SEP_RE.test(lines[i + 1]) && lines[i + 1].includes('-')) {
      const header = splitRow(line);
      const aligns = splitRow(lines[i + 1]).map((c) => (/^:-*:$/.test(c) ? 'center' : /-:$/.test(c) ? 'right' : 'left'));
      i += 2;
      const rows = [];
      while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
        const r = splitRow(lines[i]);
        while (r.length < header.length) r.push('');
        rows.push(r.slice(0, Math.max(header.length, r.length)));
        i++;
      }
      blocks.push({ t: 'table', header, aligns, rows });
      continue;
    }

    if (LIST_RE.test(line)) {
      const res = parseList(lines, i, opts);
      blocks.push(res.block); i = res.next; continue;
    }

    // paragraph
    const buf = [];
    while (i < lines.length && lines[i].trim() && (buf.length === 0 || !isBlockStart(lines[i], lines[i + 1]))) {
      buf.push(lines[i]); i++;
    }
    let text2 = '';
    buf.forEach((l, k) => {
      const hard = /( {2,}|\\)$/.test(l);
      const t = l.replace(/( {2,}|\\)$/, '').trim();
      text2 += t + (k < buf.length - 1 ? (hard ? '\n' : ' ') : '');
    });
    blocks.push({ t: 'p', text: text2 });
  }
  return blocks;
}

function normKind(k) {
  if (k === 'warning' || k === 'caution' || k === 'danger') return 'warning';
  if (k === 'tip' || k === 'hint' || k === 'success') return 'tip';
  if (k === 'important') return 'important';
  return 'note';
}

function parseList(lines, start, opts) {
  // gather raw lines belonging to the list
  const raw = [];
  let i = start;
  while (i < lines.length) {
    const l = lines[i];
    if (!l.trim()) {
      // blank: continue only if next non-blank is a list item or indented continuation
      let j = i + 1; while (j < lines.length && !lines[j].trim()) j++;
      if (j < lines.length && (LIST_RE.test(lines[j]) || indentOf(lines[j]) >= 2) && !HEAD_RE.test(lines[j])) { i = j; continue; }
      break;
    }
    if (!LIST_RE.test(l) && indentOf(l) < 2 && isBlockStart(l, lines[i + 1])) break;
    raw.push(l); i++;
  }
  // build tree
  const root = { ordered: LIST_RE.exec(raw[0])[2].match(/\d/) != null, start: 1, items: [], indent: -1 };
  const first = LIST_RE.exec(raw[0]);
  if (root.ordered) root.start = parseInt(first[2], 10);
  const stack = [{ list: root, indent: indentOf(raw[0]) }];
  let last = null;
  for (let k = 0; k < raw.length; k++) {
    const l = raw[k];
    const fm = FENCE_RE.exec(l);
    if (fm && last) {
      const fence = fm[2]; const body = []; k++;
      const closeRe = new RegExp('^\\s*' + fence[0] + '{' + fence.length + ',}\\s*$');
      while (k < raw.length && !closeRe.test(raw[k])) { body.push(raw[k].replace(new RegExp('^\\s{0,' + fm[1].length + '}'), '')); k++; }
      last.extra.push({ t: 'code', lang: fm[3].trim().split(/\s+/)[0] || '', text: body.join('\n') });
      continue;
    }
    const m = LIST_RE.exec(l);
    if (m) {
      const ind = indentOf(l);
      const ordered = /\d/.test(m[2]);
      while (stack.length > 1 && ind < stack[stack.length - 1].indent) stack.pop();
      let top = stack[stack.length - 1];
      if (ind > top.indent && top.list.items.length) {
        const sub = { ordered, start: ordered ? parseInt(m[2], 10) : 1, items: [] };
        top.list.items[top.list.items.length - 1].sub.push(sub);
        stack.push({ list: sub, indent: ind });
        top = stack[stack.length - 1];
      }
      let text = m[3];
      let task = null;
      const tm = /^\[( |x|X)\]\s+(.*)$/.exec(text);
      if (tm) { task = tm[1] !== ' '; text = tm[2]; }
      const item = { text, sub: [], extra: [], task };
      top.list.items.push(item);
      last = item;
    } else if (last) {
      last.text += ' ' + l.trim();
    }
  }
  return { block: { t: 'list', ordered: root.ordered, start: root.start, items: root.items }, next: i };
}

function resolveDiagram(info, body, opts) {
  // forms: "diagram {json}" | "diagram:file.json" | "diagram: {json}" | "diagram" + JSON body
  let rest = info.replace(/^diagram/i, '').replace(/^:/, '').trim();
  let spec;
  try {
    if (rest.startsWith('{')) spec = JSON.parse(rest);
    else if (rest) {
      const f = path.resolve(opts.baseDir || process.cwd(), rest);
      spec = JSON.parse(fs.readFileSync(f, 'utf8'));
    } else spec = JSON.parse(body);
    if (rest.startsWith('{') && body.trim()) {
      // body may supply a caption line
      spec.caption = spec.caption || body.trim();
    }
  } catch (e) {
    return { spec: null, error: String(e.message || e), caption: null };
  }
  return { spec, caption: spec.caption || null };
}

// ---------------------------------------------------------------- files
/** Natural sort (01-intro < 2-x < 10-y). */
export function naturalSort(a, b) {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

/**
 * Load a directory of .md files in order -> array of renderer parts.
 * Order: opts.order (file names, with or without .md) > `_order.txt` (one name per line, # comments)
 * > natural filename sort. Files not listed in order are appended unless opts.strict.
 */
export function loadMarkdownDir(dir, opts = {}) {
  const all = fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.md') && !/^readme\.md$/i.test(f) || (opts.includeReadme && /^readme\.md$/i.test(f))).sort(naturalSort);
  let order = opts.order;
  const of = path.join(dir, '_order.txt');
  if (!order && fs.existsSync(of)) order = fs.readFileSync(of, 'utf8').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  let files = all;
  if (order) {
    const norm = order.map((o) => (o.toLowerCase().endsWith('.md') ? o : o + '.md'));
    for (const f of norm) if (!all.includes(f)) throw new Error(`Ordered file not found in ${dir}: ${f}`);
    files = opts.strict ? norm : [...norm, ...all.filter((f) => !norm.includes(f))];
  }
  const type = opts.type || 'markdown';
  if (type === 'interview') return [{ type: 'interview', files: files.map((f) => path.join(dir, f)) }];
  return files.map((f) => ({ type: 'markdown', file: path.join(dir, f) }));
}

export function readMarkdownFile(file) {
  return fs.readFileSync(file, 'utf8').replace(/^﻿/, '');
}
