// Interview Q&A format parser + styled renderer.
//
// ### Q<NN>. <question text>
// **Tags:** <topic>, <difficulty>, <type>
// **Analogy:** ...
// **Model answer:**
// <markdown>
// **Follow-ups:** <bullets>
// **Pitfalls:** ...
import { parseMarkdown, parseInline, plainText, slugify } from './md.mjs';
import { textWidth, safe } from './fonts.mjs';

const Q_RE = /^#{2,4}\s*Q\s*(\d+)\s*[.):\-]\s*(.*?)\s*$/i;
const MARK_RE = /^\*\*\s*(tags?|analogy|model answer|answer|follow-?\s?ups?|pitfalls?|common pitfalls)\s*:?\s*\*\*\s*:?\s*(.*)$/i;
const DIFF_RE = /^(easy|medium|hard|beginner|intermediate|advanced|junior|senior|expert|basic|simple)$/i;
const FENCE_RE = /^\s*(`{3,}|~{3,})/;

const SECTION = { tag: 'tags', tags: 'tags', analogy: 'analogy', 'model answer': 'answer', answer: 'answer', pitfall: 'pitfalls', pitfalls: 'pitfalls', 'common pitfalls': 'pitfalls' };
function sectionKey(name) {
  const n = name.toLowerCase().replace(/\s+/g, ' ');
  if (/^follow/.test(n)) return 'followUps';
  return SECTION[n] || 'answer';
}

function parseQuestion(num, question, lines) {
  const q = { num: parseInt(num, 10), question, tags: [], topic: '', difficulty: '', type: '', analogy: '', answer: '', followUps: '', pitfalls: '' };
  let cur = null, fence = null;
  const buf = { tags: [], analogy: [], answer: [], followUps: [], pitfalls: [] };
  const pre = [];
  for (const line of lines) {
    const f = FENCE_RE.exec(line);
    if (f) { if (!fence) fence = f[1][0]; else if (line.trim().startsWith(fence)) fence = null; }
    const m = !fence && !f ? MARK_RE.exec(line) : null;
    if (m) { cur = sectionKey(m[1]); if (m[2] && m[2].trim()) buf[cur].push(m[2]); continue; }
    if (cur) buf[cur].push(line); else pre.push(line);
  }
  const clean = (arr) => arr.join('\n').replace(/^\s+|\s+$/g, '');
  q.analogy = clean(buf.analogy);
  q.answer = clean(buf.answer);
  if (!q.answer && pre.some((l) => l.trim())) q.answer = clean(pre); // tolerate missing **Model answer:** marker
  q.followUps = clean(buf.followUps);
  q.pitfalls = clean(buf.pitfalls);
  q.tags = clean(buf.tags).split(/[,;\n]/).map((t) => t.replace(/[`*]/g, '').trim()).filter(Boolean);
  const di = q.tags.findIndex((t) => DIFF_RE.test(t));
  if (di >= 0) q.difficulty = q.tags[di];
  const rest = q.tags.filter((_, i) => i !== di);
  q.topic = rest[0] || '';
  q.type = rest[1] || '';
  return q;
}

/** Parse interview markdown into items: {kind:'q', ...} | {kind:'h', level, text} | {kind:'md', text}. */
export function parseInterview(text) {
  const lines = String(text ?? '').replace(/\r\n?/g, '\n').split('\n');
  const items = [];
  let cur = null, mdBuf = [], fence = null;
  const flushMd = () => { if (mdBuf.some((l) => l.trim())) items.push({ kind: 'md', text: mdBuf.join('\n') }); mdBuf = []; };
  const flushQ = () => { if (cur) { items.push({ kind: 'q', ...parseQuestion(cur.num, cur.question, cur.lines) }); cur = null; } };
  for (const line of lines) {
    const f = FENCE_RE.exec(line);
    if (f) { if (!fence) fence = f[1][0]; else if (line.trim().startsWith(fence)) fence = null; }
    if (!fence && !f) {
      const q = Q_RE.exec(line);
      if (q) { flushQ(); flushMd(); cur = { num: q[1], question: q[2], lines: [] }; continue; }
      const h = /^(#{1,2})\s+(.*?)\s*#*\s*$/.exec(line);
      if (h) { flushQ(); flushMd(); items.push({ kind: 'h', level: h[1].length, text: h[2] }); continue; }
    }
    if (cur) cur.lines.push(line); else mdBuf.push(line);
  }
  flushQ(); flushMd();
  return items;
}

export function interviewToBlocks(items, opts = {}) {
  const out = [];
  for (const it of items) {
    if (it.kind === 'q') out.push({ t: 'qa', q: it });
    else if (it.kind === 'h') out.push({ t: 'h', level: it.level, text: it.text });
    else out.push(...parseMarkdown(it.text, opts));
  }
  return out;
}

const DIFF_COL = (t) => (/^(easy|beginner|basic|simple|junior)$/i.test(t) ? '#2E9D57' : /^(medium|intermediate|senior)$/i.test(t) ? '#E08A00' : '#C62828');

/** Render one question as a styled block using the Renderer R. */
export function renderQuestion(R, q) {
  const d = R.doc;
  R.stats.qa++;
  const padX = 10, padY = 8;
  const badge = 'Q' + String(q.num).padStart(2, '0');
  const badgeW = textWidth(d, 'sans-b', 10.5, badge) + 14;
  const innerW = R.w - 2 * padX - badgeW - 8;
  const qlines = R.layoutRuns(parseInline(q.question), innerW, { size: 11, bold: true, color: '#FFFFFF' });
  const chips = [];
  const names = [q.topic, q.difficulty, q.type, ...q.tags.filter((t) => ![q.topic, q.difficulty, q.type].includes(t))].filter(Boolean);
  const seen = new Set();
  for (const t of names) {
    if (seen.has(t)) continue; seen.add(t);
    const label = safe(d, 'sans-b', t);
    const color = DIFF_RE.test(t) ? DIFF_COL(t) : t === q.topic ? '#2E7D9A' : '#7E57C2';
    chips.push({ label, color, w: textWidth(d, 'sans-b', 7, label) + 12 });
  }
  // wrap chips into rows
  const rows = [[]]; let rw = 0;
  for (const c of chips) {
    if (rw + c.w > innerW && rows[rows.length - 1].length) { rows.push([]); rw = 0; }
    rows[rows.length - 1].push(c); rw += c.w + 5;
  }
  const chipRows = chips.length ? rows.length : 0;
  const hdrH = padY * 2 + qlines.length * 15 + (chipRows ? chipRows * 17 + 2 : 0);
  R.y += 8;
  R.ensure(hdrH + 90);
  if (!R.dry) {
    const x = R.x, y = R.y;
    d.save().roundedRect(x, y, R.w, hdrH, 5).fill('#1F3A5F').restore();
    d.save().roundedRect(x + padX - 2, y + padY - 1, badgeW, 19, 4).fill('#2E7D9A').restore();
    d.font('sans-b').fontSize(10.5).fillColor('#FFFFFF').text(badge, x + padX - 2 + 7, y + padY + 2, { lineBreak: false });
    qlines.forEach((l, i) => R.drawLine(l, x + padX + badgeW + 8 - 2, y + padY + i * 15, { size: 11, lh: 15, width: innerW }));
    let cy = y + padY + qlines.length * 15 + 3;
    if (chipRows) for (const row of rows) {
      let cx = x + padX + badgeW + 8 - 2;
      for (const c of row) {
        d.save().roundedRect(cx, cy, c.w, 13, 6.5).fill(c.color).restore();
        d.font('sans-b').fontSize(7).fillColor('#FFFFFF').text(c.label, cx + 6, cy + 3, { lineBreak: false });
        cx += c.w + 5;
      }
      cy += 17;
    }
    R.outlineEntry(`Q${q.num}. ${plainText(q.question)}`.slice(0, 160), 5);
    let name = 'q-' + q.num; let k = 1;
    while (R.slugs.has(name)) name = 'q-' + q.num + '-' + k++;
    R.slugs.add(name);
    d.addNamedDestination(name, 'XYZ', R.left, y - 4, null);
  }
  R.y += hdrH + 8;

  if (q.analogy) R.callout('tip', 'Analogy', parseMarkdown(q.analogy));
  if (q.answer) {
    R.para('MODEL ANSWER', { bold: true, color: '#1F3A5F', size: 8.5, after: 3 });
    R.md(q.answer);
  }
  if (q.followUps) {
    R.para('FOLLOW-UPS', { bold: true, color: '#1F3A5F', size: 8.5, after: 3 });
    R.md(q.followUps);
  }
  if (q.pitfalls) R.callout('warning', 'Pitfalls', parseMarkdown(q.pitfalls));
  R.y += 6;
  if (!R.dry) { R.ensure(4); d.save().moveTo(R.x, R.y).lineTo(R.x + R.w, R.y).lineWidth(0.4).dash(2, { space: 3 }).strokeColor('#B8C2CC').stroke().undash().restore(); }
  R.y += 8;
}
