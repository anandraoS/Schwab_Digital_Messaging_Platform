// Diagram DSL drawn with pdfkit vector primitives (no images).
// Types: boxes-and-arrows | sequence | state | er | mindmap | bar (vertical|horizontal) | hbar
// Every scene is {w, h, draw(doc), cuts?, headerH?} in local coordinates (origin top-left).
// renderDiagram(R, spec) places a scene on the page (new page if needed, scaled to width, caption).
import { safe, textWidth } from './fonts.mjs';

export const PAL = ['#2E78C7', '#2E9D57', '#E08A00', '#8E44AD', '#C62828', '#00838F', '#6D4C41', '#546E7A'];
const INK = '#222B33', GRAY = '#66717D', LINE = '#4A5561';

// ------------------------------------------------------------------ helpers
const tw = (doc, font, size, s) => textWidth(doc, font, size, s);

function hexToRgb(h) {
  const m = /^#?([0-9a-f]{6})$/i.exec(h || '');
  if (!m) return [46, 120, 199];
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
export function tint(hex, t) {
  const [r, g, b] = hexToRgb(hex);
  const f = (c) => Math.round(c + (255 - c) * t).toString(16).padStart(2, '0');
  return '#' + f(r) + f(g) + f(b);
}
function shade(hex, t) {
  const [r, g, b] = hexToRgb(hex);
  const f = (c) => Math.round(c * (1 - t)).toString(16).padStart(2, '0');
  return '#' + f(r) + f(g) + f(b);
}
function namedColor(c, def) {
  if (!c) return def;
  if (/^#[0-9a-f]{6}$/i.test(c)) return c;
  const map = { blue: PAL[0], green: PAL[1], orange: PAL[2], purple: PAL[3], red: PAL[4], teal: PAL[5], brown: PAL[6], gray: PAL[7], grey: PAL[7] };
  return map[String(c).toLowerCase()] || def;
}

export function wrapText(doc, text, font, size, maxW) {
  const s = safe(doc, font, text);
  const out = [];
  maxW = Math.max(maxW, size);
  for (const para of s.split('\n')) {
    const words = para.split(/\s+/).filter(Boolean);
    if (!words.length) { out.push(''); continue; }
    let cur = '';
    for (let w of words) {
      if (tw(doc, font, size, w) > maxW) {
        if (cur) { out.push(cur); cur = ''; }
        while (tw(doc, font, size, w) > maxW && w.length > 1) {
          let lo = 1, hi = w.length - 1;
          while (lo < hi) { const m = (lo + hi + 1) >> 1; if (tw(doc, font, size, w.slice(0, m)) <= maxW) lo = m; else hi = m - 1; }
          out.push(w.slice(0, lo)); w = w.slice(lo);
        }
      }
      const t = cur ? cur + ' ' + w : w;
      if (!cur || tw(doc, font, size, t) <= maxW) cur = t; else { out.push(cur); cur = w; }
    }
    out.push(cur);
  }
  return out;
}

/** Fit text into w x h: shrink font from `size` to `min`, wrap; truncate with ellipsis as last resort. */
export function fitText(doc, text, { w, h = 1e9, size = 9, min = 6, font = 'sans', lh = 1.2 }) {
  let s = size, lines;
  const words = safe(doc, font, text).split(/\s+/).filter(Boolean);
  for (; s >= min - 0.001; s -= 0.5) {
    const longest = Math.max(0, ...words.map((x) => tw(doc, font, s, x)));
    if (longest > w + 0.01 && s - 0.5 >= min - 0.001) continue; // shrink before breaking a word
    lines = wrapText(doc, text, font, s, w);
    if (lines.length * s * lh <= h + 0.01) return { size: s, lines, lh: s * lh, h: lines.length * s * lh, font };
  }
  s = min; lines = wrapText(doc, text, font, s, w);
  const max = Math.max(1, Math.floor(h / (s * lh)));
  if (lines.length > max) {
    lines = lines.slice(0, max);
    let last = lines[max - 1];
    while (last.length > 1 && tw(doc, font, s, last + '...') > w) last = last.slice(0, -1);
    lines[max - 1] = last + '...';
  }
  return { size: s, lines, lh: s * lh, h: lines.length * s * lh, font };
}

function drawStr(doc, str, x, y, { font = 'sans', size = 8, color = INK, align = 'left', w = 0 } = {}) {
  const s = safe(doc, font, str);
  const width = tw(doc, font, size, s);
  let xx = x;
  if (align === 'center') xx = x + (w - width) / 2; else if (align === 'right') xx = x + w - width;
  doc.font(font).fontSize(size).fillColor(color).text(s, xx, y, { lineBreak: false });
  return width;
}
function drawFit(doc, fit, x, y, w, h, { color = INK, align = 'center', valign = 'middle' } = {}) {
  let yy = y;
  if (valign === 'middle') yy = y + (h - fit.h) / 2; else if (valign === 'bottom') yy = y + h - fit.h;
  fit.lines.forEach((l, i) => drawStr(doc, l, x, yy + i * fit.lh, { font: fit.font, size: fit.size, color, align, w }));
}
function rrect(doc, x, y, w, h, r, fill, stroke, lw = 0.8) {
  doc.save();
  doc.roundedRect(x, y, w, h, r);
  if (fill && stroke) doc.lineWidth(lw).fillAndStroke(fill, stroke);
  else if (fill) doc.fill(fill); else doc.lineWidth(lw).stroke(stroke);
  doc.restore();
}
function arrowHead(doc, x, y, dx, dy, { size = 6, color = LINE, open = false } = {}) {
  const l = Math.hypot(dx, dy) || 1; dx /= l; dy /= l;
  const bx = x - dx * size, by = y - dy * size, px = -dy * size * 0.45, py = dx * size * 0.45;
  doc.save();
  if (open) doc.lineWidth(0.9).strokeColor(color).moveTo(bx + px, by + py).lineTo(x, y).lineTo(bx - px, by - py).stroke();
  else doc.polygon([x, y], [bx + px, by + py], [bx - px, by - py]).fill(color);
  doc.restore();
}
function polyline(doc, pts, { color = LINE, width = 1, dash = false } = {}) {
  doc.save().lineWidth(width).strokeColor(color).lineJoin('round');
  if (dash) doc.dash(3.5, { space: 2.5 });
  doc.moveTo(pts[0].x, pts[0].y);
  for (let i = 1; i < pts.length; i++) doc.lineTo(pts[i].x, pts[i].y);
  doc.stroke().undash().restore();
}
function labelDims(doc, str, { size = 7, font = 'sans-i', maxW = 130 } = {}) {
  const f = fitText(doc, str, { w: maxW, h: size * 1.2 * 2, size, min: 5.5, font });
  const w = Math.max(...f.lines.map((l) => tw(doc, font, f.size, l))) + 4, h = f.h + 2;
  return { f, w, h };
}
function labelBox(doc, str, cx, cy, o = {}) {
  const { f, w, h } = labelDims(doc, str, o);
  doc.save().fillOpacity(0.92).roundedRect(cx - w / 2, cy - h / 2, w, h, 2).fill('#FFFFFF').restore();
  drawFit(doc, f, cx - w / 2, cy - h / 2, w, h, { color: o.color || INK });
  return { w, h };
}
const rectHit = (r, x, y, m = 0) => x > r.x - m && x < r.x + r.w + m && y > r.y - m && y < r.y + r.h + m;
const rectsOverlap = (a, b, m = 0) => a.x < b.x + b.w + m && a.x + a.w > b.x - m && a.y < b.y + b.h + m && a.y + a.h > b.y - m;
function longestSeg(pts) {
  let best = 0, bi = 0;
  for (let i = 0; i < pts.length - 1; i++) { const l = Math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y); if (l > best) { best = l; bi = i; } }
  const a = pts[bi], b = pts[bi + 1];
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, vertical: Math.abs(a.x - b.x) < 0.5 && Math.abs(a.y - b.y) > 0.5, len: best };
}

// ------------------------------------------------------------------ boxes-and-arrows
function nodeFit(doc, node, w) {
  const label = fitText(doc, node.label ?? node.id, { w: w - 10, h: 3 * 8.5 * 1.2, size: 8.5, min: 6.5, font: 'sans-b' });
  const sub = node.sub ? fitText(doc, node.sub, { w: w - 10, h: 2 * 7 * 1.2, size: 7, min: 5.5, font: 'sans' }) : null;
  return { label, sub, need: 10 + label.h + (sub ? 3 + sub.h : 0) };
}

function sceneBoxes(doc, spec, W) {
  const dir = spec.direction === 'LR' ? 'LR' : 'TB';
  const lanes = (spec.lanes || [{ id: 'main', label: '', nodes: spec.nodes || [] }]).map((l, i) => ({ ...l, color: namedColor(l.color, PAL[i % PAL.length]) }));
  const L = lanes.length;
  const nodes = new Map();
  lanes.forEach((l, li) => l.nodes.forEach((n, ni) => nodes.set(n.id, { ...n, lane: li, idx: ni })));
  const edges = (spec.edges || []).filter((e) => nodes.has(e.from) && nodes.has(e.to));
  const hasLabel = lanes.some((l) => l.label);
  const anyEdgeLabel = edges.some((e) => e.label);
  const G = anyEdgeLabel ? 36 : 24;
  const slotsOf = (l) => Math.max(l.nodes.length, ...l.nodes.map((n) => (n.col != null ? n.col + 1 : 0)));
  const maxSlots = Math.max(1, ...lanes.map(slotsOf));
  let H, nodeW, ch;
  const rects = new Map();
  const bands = [];
  if (dir === 'TB') {
    const labelW = hasLabel ? 64 : 0, inset = 8;
    const usable = W - labelW - 2 * inset;
    const slotW = usable / maxSlots, gapX = Math.min(26, Math.max(10, slotW * 0.16));
    nodeW = slotW - gapX;
    ch = 34;
    for (const n of nodes.values()) { n.fit = nodeFit(doc, n, nodeW); ch = Math.max(ch, n.fit.need); }
    const laneH = ch + 16;
    lanes.forEach((l, li) => {
      const top = li * (laneH + G);
      bands.push({ x: 0, y: top, w: W, h: laneH, lane: l, labelW, vertical: false });
      const k = l.nodes.length, off = l.nodes.some((n) => n.col != null) ? 0 : (maxSlots - k) / 2;
      l.nodes.forEach((n, ni) => {
        const s = n.col != null ? n.col : off + ni;
        rects.set(n.id, { x: labelW + inset + s * slotW + gapX / 2, y: top + (laneH - ch) / 2, w: nodeW, h: ch });
      });
    });
    H = L * laneH + (L - 1) * G;
  } else {
    const laneW = (W - (L - 1) * G) / L;
    nodeW = laneW - 16;
    const hH = hasLabel ? 24 : 0, vg = 14;
    ch = 30;
    for (const n of nodes.values()) { n.fit = nodeFit(doc, n, nodeW); ch = Math.max(ch, n.fit.need); }
    H = hH + vg + maxSlots * (ch + vg);
    lanes.forEach((l, li) => {
      const left = li * (laneW + G);
      bands.push({ x: left, y: 0, w: laneW, h: H, lane: l, headerH: hH, vertical: true });
      const k = l.nodes.length, off = l.nodes.some((n) => n.col != null) ? 0 : (maxSlots - k) / 2;
      l.nodes.forEach((n, ni) => {
        const s = n.col != null ? n.col : off + ni;
        rects.set(n.id, { x: left + 8, y: hH + vg + s * (ch + vg), w: nodeW, h: ch });
      });
    });
  }
  // ---- edge routing
  const A = dir === 'TB'
    ? { p0: (r) => r.y, p1: (r) => r.y + r.h, s0: (r) => r.x, s1: (r) => r.x + r.w, pt: (p, s) => ({ x: s, y: p }), sc: (r) => r.x + r.w / 2 }
    : { p0: (r) => r.x, p1: (r) => r.x + r.w, s0: (r) => r.y, s1: (r) => r.y + r.h, pt: (p, s) => ({ x: p, y: s }), sc: (r) => r.y + r.h / 2 };
  const SECMAX = dir === 'TB' ? W : H;
  const info = edges.map((e, ei) => {
    const u = nodes.get(e.from), v = nodes.get(e.to), ru = rects.get(u.id), rv = rects.get(v.id);
    let kind, sideU, sideV;
    if (u.id === v.id) { kind = 'self'; sideU = sideV = 'p0'; }
    else if (u.lane !== v.lane) {
      kind = 'cross'; const fwd = u.lane < v.lane;
      sideU = fwd ? 'p1' : 'p0'; sideV = fwd ? 'p0' : 'p1';
    } else {
      const between = [...nodes.values()].some((n) => n.lane === u.lane && n.id !== u.id && n.id !== v.id && A.sc(rects.get(n.id)) > Math.min(A.sc(ru), A.sc(rv)) && A.sc(rects.get(n.id)) < Math.max(A.sc(ru), A.sc(rv)));
      if (between) { kind = 'arc'; sideU = sideV = 'p0'; } else { kind = 'side'; const right = A.sc(rv) > A.sc(ru); sideU = right ? 's1' : 's0'; sideV = right ? 's0' : 's1'; }
    }
    return { e, ei, u, v, ru, rv, kind, sideU, sideV };
  });
  const ports = new Map();
  const addPort = (id, side, ref, other) => { const k = id + '|' + side; if (!ports.has(k)) ports.set(k, []); ports.get(k).push({ ref, other }); };
  info.forEach((o) => { addPort(o.u.id, o.sideU, o.ei + 'u', A.sc(o.rv)); addPort(o.v.id, o.sideV, o.ei + 'v', A.sc(o.ru)); });
  const portPos = new Map();
  for (const [k, list] of ports) {
    const [id, side] = k.split('|'); const r = rects.get(id);
    list.sort((a, b) => a.other - b.other);
    list.forEach((it, i) => {
      const frac = (i + 1) / (list.length + 1);
      const along = side[0] === 'p' ? A.s0(r) + frac * (A.s1(r) - A.s0(r)) : A.p0(r) + frac * (A.p1(r) - A.p0(r));
      portPos.set(it.ref, along);
    });
  }
  const free = (pref, laneLo, laneHi) => {
    const blocked = [];
    for (const n of nodes.values()) if (n.lane > laneLo && n.lane < laneHi) { const r = rects.get(n.id); blocked.push([A.s0(r) - 7, A.s1(r) + 7]); }
    const ok = (x) => x > 4 && x < SECMAX - 4 && !blocked.some(([a, b]) => x >= a && x <= b);
    for (let d = 0; d < SECMAX; d += 3) { if (ok(pref + d)) return pref + d; if (ok(pref - d)) return pref - d; }
    return pref;
  };
  const routes = info.map((o) => {
    const { u, v, ru, rv, ei } = o;
    const su = portPos.get(ei + 'u'), sv = portPos.get(ei + 'v');
    const stag = ((ei % 3) - 1) * 4;
    let pts;
    const edgeOfSide = (r, side, along) => (side[0] === 'p' ? A.pt(side === 'p0' ? A.p0(r) : A.p1(r), along) : A.pt(along, side === 's0' ? A.s0(r) : A.s1(r)));
    if (o.kind === 'cross') {
      const fwd = u.lane < v.lane, sgn = fwd ? 1 : -1;
      const pe = fwd ? A.p1(ru) : A.p0(ru), pn = fwd ? A.p0(rv) : A.p1(rv);
      const pm0 = pe + sgn * (G / 2 + stag), pm1 = pn - sgn * (G / 2 - stag);
      if (Math.abs(su - sv) < 0.5) pts = [A.pt(pe, su), A.pt(pn, sv)];
      else if (Math.abs(u.lane - v.lane) === 1) pts = [A.pt(pe, su), A.pt(pm0, su), A.pt(pm0, sv), A.pt(pn, sv)];
      else {
        const sc = free((su + sv) / 2, Math.min(u.lane, v.lane), Math.max(u.lane, v.lane));
        pts = [A.pt(pe, su), A.pt(pm0, su), A.pt(pm0, sc), A.pt(pm1, sc), A.pt(pm1, sv), A.pt(pn, sv)];
      }
    } else if (o.kind === 'side') {
      pts = [edgeOfSide(ru, o.sideU, su), edgeOfSide(rv, o.sideV, sv)];
    } else if (o.kind === 'arc') {
      const out = 12 + stag;
      pts = [A.pt(A.p0(ru), su), A.pt(A.p0(ru) - out, su), A.pt(A.p0(rv) - out, sv), A.pt(A.p0(rv), sv)];
    } else { // self
      const r = ru; const a = A.pt(A.p0(r), A.s0(r) + (A.s1(r) - A.s0(r)) * 0.3), b = A.pt(A.p0(r), A.s0(r) + (A.s1(r) - A.s0(r)) * 0.7);
      const out = 12;
      pts = [a, A.pt(A.p0(r) - out, A.s0(r) + (A.s1(r) - A.s0(r)) * 0.3), A.pt(A.p0(r) - out, A.s0(r) + (A.s1(r) - A.s0(r)) * 0.7), b];
    }
    return pts;
  });
  const draw = (d) => {
    for (const b of bands) {
      const c = b.lane.color;
      rrect(d, b.x, b.y, b.w, b.h, 5, tint(c, 0.93), tint(c, 0.6), 0.6);
      if (b.lane.label) {
        if (!b.vertical) {
          const f = fitText(d, b.lane.label, { w: b.labelW - 10, h: b.h - 8, size: 8, min: 6, font: 'sans-b' });
          drawFit(d, f, b.x + 5, b.y, b.labelW - 10, b.h, { color: shade(c, 0.25) });
        } else {
          const f = fitText(d, b.lane.label, { w: b.w - 10, h: b.headerH - 4, size: 8.5, min: 6, font: 'sans-b' });
          drawFit(d, f, b.x + 5, b.y + 2, b.w - 10, b.headerH - 4, { color: shade(c, 0.25) });
        }
      }
    }
    // edges beneath nodes
    routes.forEach((pts, i) => {
      const e = info[i].e, col = namedColor(e.color, LINE);
      polyline(d, pts, { color: col, width: 1, dash: e.style === 'dashed' });
      const n = pts.length;
      arrowHead(d, pts[n - 1].x, pts[n - 1].y, pts[n - 1].x - pts[n - 2].x, pts[n - 1].y - pts[n - 2].y, { color: col });
      if (e.bidirectional) arrowHead(d, pts[0].x, pts[0].y, pts[0].x - pts[1].x, pts[0].y - pts[1].y, { color: col });
    });
    for (const n of nodes.values()) {
      const r = rects.get(n.id), c = namedColor(n.color, lanes[n.lane].color);
      rrect(d, r.x, r.y, r.w, r.h, 4, tint(c, 0.82), c, 1);
      const total = n.fit.label.h + (n.fit.sub ? 3 + n.fit.sub.h : 0);
      const y0 = r.y + (r.h - total) / 2;
      drawFit(d, n.fit.label, r.x, y0, r.w, n.fit.label.h, { color: shade(c, 0.45), valign: 'top' });
      if (n.fit.sub) drawFit(d, n.fit.sub, r.x, y0 + n.fit.label.h + 3, r.w, n.fit.sub.h, { color: GRAY, valign: 'top' });
    }
    routes.forEach((pts, i) => {
      const e = info[i].e;
      if (!e.label) return;
      const m = longestSeg(pts);
      const w = Math.min(130, 140);
      labelBox(d, e.label, m.x + (m.vertical ? 4 : 0) + (m.vertical ? Math.min(tw(d, 'sans-i', 7, safe(d, 'sans-i', e.label)), w) / 2 + 4 : 0), m.y, { maxW: w });
    });
  };
  return { w: W, h: H, draw };
}

// ------------------------------------------------------------------ sequence
function sceneSequence(doc, spec, W) {
  const parts = spec.participants.map((p) => (typeof p === 'string' ? { id: p, label: p } : { label: p.id, ...p }));
  const n = parts.length, cw = W / n;
  const idx = new Map(parts.map((p, i) => [p.id, i]));
  const cx = (i) => cw * (i + 0.5);
  const bw = Math.min(cw - 8, 140);
  let hh = 24;
  const hfits = parts.map((p) => { const f = fitText(doc, p.label, { w: bw - 8, h: 3 * 8.5 * 1.2, size: 8.5, min: 6.5, font: 'sans-b' }); hh = Math.max(hh, f.h + 10); return f; });
  const ops = []; const cuts = [];
  let y = hh + 12; let num = 1;
  const SZ = 8;
  const lay = (steps, depth) => {
    for (const st of steps) {
      if (st.note !== undefined) {
        const over = [].concat(st.over ?? parts[0].id).map((id) => idx.get(id)).filter((v) => v !== undefined);
        const lo = Math.min(...over), hi = Math.max(...over);
        let x0 = cx(lo) - (hi === lo ? Math.min(cw * 0.95, 150) / 2 : cw * 0.45), x1 = cx(hi) + (hi === lo ? Math.min(cw * 0.95, 150) / 2 : cw * 0.45);
        x0 = Math.max(2, x0); x1 = Math.min(W - 2, x1);
        const f = fitText(doc, st.note, { w: x1 - x0 - 10, size: SZ, min: 6.5, font: 'sans' });
        const h = f.h + 8, yy = y;
        ops.push({ draw: (d) => { d.save().polygon([x0, yy], [x1 - 7, yy], [x1, yy + 7], [x1, yy + h], [x0, yy + h]).lineWidth(0.7).fillAndStroke('#FFF8D6', '#D4BE62').restore(); drawFit(d, f, x0 + 5, yy + 4, x1 - x0 - 10, f.h, { align: 'left', valign: 'top', color: '#5B4B00' }); } });
        y += h + 8;
      } else if (st.alt !== undefined || st.loop !== undefined || st.opt !== undefined || st.group !== undefined) {
        const kw = st.alt !== undefined ? 'alt' : st.loop !== undefined ? 'loop' : st.opt !== undefined ? 'opt' : 'group';
        const cond = st[kw];
        const ins = depth * 7;
        const fx0 = Math.max(1, cx(0) - bw / 2 + ins - 4), fx1 = Math.min(W - 1, cx(n - 1) + bw / 2 - ins + 4);
        const top = y; y += 6;
        const label = kw + (cond ? ' [' + cond + ']' : '');
        const lf = fitText(doc, label, { w: Math.max(60, fx1 - fx0 - 20), h: 12, size: 7.5, min: 6, font: 'sans-b' });
        y += lf.h > 11 ? 10 : 6;
        lay(st.steps || [], depth + 1);
        const dividers = [];
        if (st.else) {
          const elses = Array.isArray(st.else) ? st.else : [st.else];
          for (const el of elses) {
            const ey = y; y += 14;
            dividers.push({ y: ey, text: el.label ?? 'else' });
            lay(el.steps || [], depth + 1);
          }
        }
        y += 4;
        const bottom = y;
        ops.push({ draw: (d) => {
          d.save().lineWidth(0.8).strokeColor('#7A8795').rect(fx0, top, fx1 - fx0, bottom - top).stroke().restore();
          const tabW = Math.min(tw(d, 'sans-b', lf.size, safe(d, 'sans-b', lf.lines[0])) + 10, fx1 - fx0);
          d.save().polygon([fx0, top], [fx0 + tabW, top], [fx0 + tabW, top + lf.h + 2], [fx0 + tabW - 5, top + lf.h + 5], [fx0, top + lf.h + 5]).fillAndStroke('#E9EDF2', '#7A8795').restore();
          drawStr(d, lf.lines[0], fx0 + 4, top + 2, { font: 'sans-b', size: lf.size, color: INK });
          for (const dv of dividers) {
            d.save().dash(4, { space: 3 }).lineWidth(0.7).strokeColor('#7A8795').moveTo(fx0, dv.y + 2).lineTo(fx1, dv.y + 2).stroke().undash().restore();
            drawStr(d, '[' + dv.text + ']', fx0 + 5, dv.y + 4, { font: 'sans-i', size: 7, color: GRAY });
          }
        } });
        y += 8;
      } else {
        const from = idx.get(st.from), to = idx.get(st.to);
        if (from === undefined || to === undefined) continue;
        const kind = st.kind || (st.dashed ? 'return' : 'sync');
        const text = (spec.autonumber ? (num++) + '. ' : '') + (st.text ?? '');
        if (from === to) {
          const x = cx(from), right = W - x > 120;
          const lw = Math.max(50, Math.min(170, right ? W - x - 30 : x - 30));
          const f = fitText(doc, text, { w: lw, size: SZ, min: 6.5, font: 'sans' });
          const h = Math.max(f.h, 16) + 8, yy = y;
          ops.push({ draw: (d) => {
            const dx = right ? 22 : -22;
            const pts = [{ x, y: yy + 3 }, { x: x + dx, y: yy + 3 }, { x: x + dx, y: yy + 17 }, { x: x + 1, y: yy + 17 }];
            polyline(d, pts, { color: LINE, width: 1, dash: kind === 'return' });
            arrowHead(d, x + 1, yy + 17, -dx, 0, { open: kind !== 'sync' });
            drawFit(d, f, right ? x + 27 : x - 27 - lw, yy, lw, f.h, { align: right ? 'left' : 'right', valign: 'top' });
          } });
          y += Math.max(h, 26);
        } else {
          const x1 = cx(from), x2 = cx(to), span = Math.abs(x2 - x1);
          const f = fitText(doc, text, { w: Math.max(36, span - 10), size: SZ, min: 6.5, font: 'sans' });
          const yy = y, ay = y + f.h + 8;
          ops.push({ draw: (d) => {
            polyline(d, [{ x: x1, y: ay }, { x: x2, y: ay }], { color: LINE, width: 1, dash: kind === 'return' });
            arrowHead(d, x2, ay, x2 - x1, 0, { open: kind !== 'sync' });
            drawFit(d, f, Math.min(x1, x2) + 3, yy, span - 6, f.h, { align: 'center', valign: 'top' });
          } });
          y += f.h + 17;
        }
      }
      if (depth === 0) cuts.push(y);
    }
  };
  lay(spec.steps || [], 0);
  const H = y + 6;
  const drawHeader = (d) => {
    parts.forEach((p, i) => {
      const c = namedColor(p.color, PAL[i % PAL.length]);
      rrect(d, cx(i) - bw / 2, 0, bw, hh, 4, tint(c, 0.82), c, 1);
      drawFit(d, hfits[i], cx(i) - bw / 2, 0, bw, hh, { color: shade(c, 0.45) });
    });
  };
  const draw = (d) => {
    d.save().dash(2.5, { space: 3 }).lineWidth(0.6).strokeColor('#9AA5B1');
    for (let i = 0; i < n; i++) d.moveTo(cx(i), hh).lineTo(cx(i), H).stroke();
    d.undash().restore();
    drawHeader(d);
    for (const op of ops) op.draw(d);
  };
  return { w: W, h: H, draw, cuts, headerH: hh + 6, drawHeader };
}

// ------------------------------------------------------------------ state machine
function rectEdgePoint(r, from, toward) { // point on rect boundary along ray from center `from` toward `toward`
  const dx = toward.x - from.x, dy = toward.y - from.y;
  if (!dx && !dy) return { ...from };
  const hw = r.w / 2, hh = r.h / 2;
  const t = Math.min(dx ? hw / Math.abs(dx) : Infinity, dy ? hh / Math.abs(dy) : Infinity);
  return { x: from.x + dx * t, y: from.y + dy * t };
}
const KIND = { ok: '#2E9D57', success: '#2E9D57', error: '#C62828', fail: '#C62828', warn: '#E08A00', warning: '#E08A00', neutral: '#546E7A', info: '#2E78C7' };

function sceneState(doc, spec, W) {
  const states = spec.states.map((s) => (typeof s === 'string' ? { id: s, label: s } : { label: s.id, ...s }));
  const byId = new Map(states.map((s) => [s.id, s]));
  const trans = (spec.transitions || []).filter((t) => byId.has(t.from) && byId.has(t.to));
  const out = new Map(), inc = new Map();
  trans.forEach((t) => { out.set(t.from, (out.get(t.from) || 0) + 1); inc.set(t.to, (inc.get(t.to) || 0) + 1); });
  states.forEach((s) => {
    s.color = namedColor(KIND[s.kind] || s.color, PAL[0]);
    if (s.terminal === undefined) s.terminal = spec.autoTerminal !== false && !out.get(s.id) && !!inc.get(s.id);
    if (s.initial === undefined) s.initial = false;
  });
  const roots = states.filter((s) => s.initial);
  if (!roots.length) { const r = states.find((s) => !inc.get(s.id)) || states[0]; if (r) r.initial = true; roots.push(r); }
  // layering: DFS finds back-edges, then longest path on the remaining DAG
  const adj = new Map(states.map((s) => [s.id, []]));
  trans.forEach((t, i) => { if (t.from !== t.to) adj.get(t.from).push({ to: t.to, i }); });
  const color = new Map(), back = new Set();
  const dfs = (u) => { color.set(u, 1); for (const e of adj.get(u)) { if (color.get(e.to) === 1) back.add(e.i); else if (!color.get(e.to)) dfs(e.to); } color.set(u, 2); };
  roots.forEach((r) => { if (!color.get(r.id)) dfs(r.id); });
  states.forEach((s) => { if (!color.get(s.id)) dfs(s.id); });
  const layer = new Map(states.map((s) => [s.id, 0]));
  for (let it = 0; it < states.length; it++) {
    let ch = false;
    trans.forEach((t, i) => { if (t.from === t.to || back.has(i)) return; if (layer.get(t.to) < layer.get(t.from) + 1) { layer.set(t.to, layer.get(t.from) + 1); ch = true; } });
    if (!ch) break;
  }
  const mode = spec.layout || 'layers';
  const needsMargin = trans.some((t, i) => back.has(i) || Math.abs(layer.get(t.to) - layer.get(t.from)) > 1);
  const mX = mode === 'layers' && needsMargin ? 44 : 6;
  const WL = W - 2 * mX;
  const rects = new Map();
  const nodeFitS = (s, w) => fitText(doc, s.label, { w: w - 10, h: 3 * 8.5 * 1.2, size: 8.5, min: 6.5, font: 'sans-b' });
  let H;
  const topPad = 26;
  if (mode === 'circle') {
    const n = states.length;
    const nw = Math.min(110, Math.max(70, W / 4.2));
    states.forEach((s) => { s.fit = nodeFitS(s, nw); });
    const nh = Math.max(30, ...states.map((s) => s.fit.h + 12));
    const R = Math.min(W / 2 - nw / 2 - 6, Math.max(110, n * 55));
    const Ry = Math.min(R * 0.85, Math.max(70, n * 28));
    H = 2 * Ry + nh + 2 * topPad;
    states.forEach((s, i) => { const a = -Math.PI / 2 + (2 * Math.PI * i) / n; rects.set(s.id, { x: W / 2 + R * Math.cos(a) - nw / 2, y: H / 2 + Ry * Math.sin(a) - nh / 2, w: nw, h: nh }); });
  } else {
    const hasPos = states.some((s) => s.pos);
    const grid = new Map();
    if (hasPos) states.forEach((s, i) => grid.set(s.id, s.pos ? { c: s.pos[0], r: s.pos[1] } : { c: i % 3, r: Math.floor(i / 3) }));
    else {
      const lanes = new Map();
      states.forEach((s) => { const l = layer.get(s.id); if (!lanes.has(l)) lanes.set(l, []); lanes.get(l).push(s); });
      const dirLR = spec.direction === 'LR';
      const maxK = Math.max(...[...lanes.values()].map((a) => a.length));
      const parents = new Map(states.map((s) => [s.id, []]));
      trans.forEach((t, i) => { if (t.from !== t.to && !back.has(i)) parents.get(t.to).push(t.from); });
      const posOf = new Map();
      for (const l of [...lanes.keys()].sort((a, b) => a - b)) {
        const arr = lanes.get(l);
        const want = arr.map((s, idx) => { const ps = parents.get(s.id).filter((p) => posOf.has(p)); return { s, idx, w: ps.length ? ps.reduce((a, p) => a + posOf.get(p), 0) / ps.length : (maxK - 1) / 2 }; });
        want.sort((a, b) => a.w - b.w || a.idx - b.idx);
        let prev = -Infinity; const ps = [];
        for (const it of want) { const p = Math.max(it.w, prev + 1); ps.push(p); prev = p; }
        // shift left if beyond the right edge, then clamp at 0
        const over = ps[ps.length - 1] - (maxK - 1);
        if (over > 0) for (let i = 0; i < ps.length; i++) ps[i] -= over;
        for (let i = 0; i < ps.length; i++) if (ps[i] < 0) { const d = -ps[i]; for (let j = i; j < ps.length; j++) ps[j] += d; }
        want.forEach((it, i) => { posOf.set(it.s.id, ps[i]); grid.set(it.s.id, dirLR ? { c: l, r: ps[i] } : { c: ps[i], r: l }); });
      }
    }
    const cols = Math.max(...[...grid.values()].map((g) => g.c)) + 1, rows = Math.max(...[...grid.values()].map((g) => g.r)) + 1;
    const gapX = Math.max(26, spec.gapX ?? 36);
    const nw = Math.min(spec.nodeWidth || 118, (WL - (cols - 1) * gapX) / cols);
    states.forEach((s) => { s.fit = nodeFitS(s, nw); });
    const nh = Math.max(30, ...states.map((s) => s.fit.h + 12));
    const gapY = spec.gapY ?? 46;
    const gx = cols > 1 ? Math.max(gapX, (WL - cols * nw) / (cols - 1)) : 0;
    const offX = mX + (WL - (cols * nw + Math.max(0, cols - 1) * gx)) / 2;
    H = rows * nh + (rows - 1) * gapY + 2 * topPad;
    states.forEach((s) => { const g = grid.get(s.id); rects.set(s.id, { x: offX + g.c * (nw + gx), y: topPad + g.r * (nh + gapY), w: nw, h: nh }); });
  }
  const ctr = (r) => ({ x: r.x + r.w / 2, y: r.y + r.h / 2 });
  const pairKey = (a, b) => (a < b ? a + '\u0000' + b : b + '\u0000' + a);
  const pairCount = new Map(), pairSeen = new Map();
  trans.forEach((t) => { const k = pairKey(t.from, t.to); pairCount.set(k, (pairCount.get(k) || 0) + 1); });
  const quad = (p0, c, p1, t) => ({ x: (1 - t) * (1 - t) * p0.x + 2 * (1 - t) * t * c.x + t * t * p1.x, y: (1 - t) * (1 - t) * p0.y + 2 * (1 - t) * t * c.y + t * t * p1.y });
  const CANDS = [0, 14, -14, 28, -28, 44, -44, 60, -60, 80, -80, 100, -100, 125, -125];
  const placedLabels = [];
  const curves = trans.map((t, i) => {
    const ra = rects.get(t.from), rb = rects.get(t.to);
    if (t.from === t.to) return { t, self: true, ra };
    const a = ctr(ra), b = ctr(rb);
    const k = pairKey(t.from, t.to), cnt = pairCount.get(k), seen = pairSeen.get(k) || 0; pairSeen.set(k, seen + 1);
    const dx = b.x - a.x, dy = b.y - a.y, len = Math.hypot(dx, dy) || 1;
    const px = -dy / len, py = dx / len;
    const base = cnt > 1 ? (seen - (cnt - 1) / 2) * 34 : 0;
    const others = [...rects.entries()].filter(([id]) => id !== t.from && id !== t.to).map(([, r]) => r);
    let best = null;
    for (const cand of CANDS) {
      const off = base + cand;
      const ctrl = { x: (a.x + b.x) / 2 + px * off * 2, y: (a.y + b.y) / 2 + py * off * 2 };
      const p0 = rectEdgePoint(ra, a, ctrl), p1 = rectEdgePoint(rb, b, ctrl);
      let hits = 0, oob = 0;
      for (let j = 1; j < 24; j++) {
        const pt = quad(p0, ctrl, p1, j / 24);
        if (others.some((r) => rectHit(r, pt.x, pt.y, 5))) hits++;
        if (pt.x < 4 || pt.x > W - 4 || pt.y < 2 || pt.y > H - 2) oob++;
      }
      const cost = hits * 1000 + oob * 400 + Math.abs(cand) * 0.6 + (back.has(i) && cand * Math.sign(base || 1) < 0 ? 5 : 0);
      if (!best || cost < best.cost) best = { cost, ctrl, p0, p1 };
      if (hits === 0 && oob === 0 && cand === 0) break;
    }
    return { t, p0: best.p0, p1: best.p1, ctrl: best.ctrl };
  });
  // label positions: pick a curve parameter whose label box avoids nodes and earlier labels
  const nodeRects = [...rects.values()];
  for (const c of curves) {
    if (c.self || !c.t.label) continue;
    const dims = labelDims(doc, c.t.label, { maxW: 110 });
    let chosen = null;
    const cdx = c.p1.x - c.p0.x, cdy = c.p1.y - c.p0.y, cl = Math.hypot(cdx, cdy) || 1;
    const nx = -cdy / cl, ny = cdx / cl;
    outer: for (const sh of [0, 1, -1]) {
      for (const tt of [0.5, 0.38, 0.62, 0.28, 0.72, 0.2, 0.8]) {
        const q = quad(c.p0, c.ctrl, c.p1, tt);
        const pt = { x: q.x + nx * sh * (dims.h / 2 + 4), y: q.y + ny * sh * (dims.h / 2 + 4) };
        const box = { x: pt.x - dims.w / 2, y: pt.y - dims.h / 2, w: dims.w, h: dims.h };
        if (!nodeRects.some((r) => rectsOverlap(box, r, 2)) && !placedLabels.some((r) => rectsOverlap(box, r, 1))) { chosen = { pt, box }; break outer; }
      }
    }
    if (!chosen) { const pt = quad(c.p0, c.ctrl, c.p1, 0.5); chosen = { pt, box: { x: pt.x - dims.w / 2, y: pt.y - dims.h / 2, w: dims.w, h: dims.h } }; }
    placedLabels.push(chosen.box);
    c.lab = chosen.pt;
  }
  const draw = (d) => {
    for (const c of curves) {
      const col = namedColor(c.t.color, LINE);
      if (c.self) {
        const r = c.ra, x = r.x + r.w, y = r.y + 5;
        d.save().lineWidth(1).strokeColor(col).moveTo(x - 10, r.y).bezierCurveTo(x - 10, r.y - 22, x + 24, r.y - 8, x, y + 8).stroke().restore();
        arrowHead(d, x, y + 8, -6, 4, { color: col });
        if (c.t.label) { const f = fitText(d, c.t.label, { w: 110, h: 10, size: 7, min: 5.5, font: 'sans-i' }); drawFit(d, f, x + 6, r.y - 16, 110, f.h, { align: 'left', valign: 'top', color: '#333' }); }
        continue;
      }
      d.save().lineWidth(1).strokeColor(col).moveTo(c.p0.x, c.p0.y).quadraticCurveTo(c.ctrl.x, c.ctrl.y, c.p1.x, c.p1.y).stroke().restore();
      arrowHead(d, c.p1.x, c.p1.y, c.p1.x - c.ctrl.x, c.p1.y - c.ctrl.y, { color: col });
    }
    for (const s of states) {
      const r = rects.get(s.id), c = s.color;
      if (s.terminal) {
        rrect(d, r.x - 3, r.y - 3, r.w + 6, r.h + 6, 8, null, c, 0.9);
        rrect(d, r.x, r.y, r.w, r.h, 6, tint(c, 0.75), c, 1.6);
      } else rrect(d, r.x, r.y, r.w, r.h, 6, tint(c, 0.85), c, 1);
      drawFit(d, s.fit, r.x, r.y, r.w, r.h, { color: shade(c, 0.5) });
      if (s.initial) {
        const cx0 = r.x + r.w / 2;
        d.save().circle(cx0, r.y - 17, 4.5).fill(INK).restore();
        polyline(d, [{ x: cx0, y: r.y - 12.5 }, { x: cx0, y: r.y - 4 }], { color: INK, width: 1 });
        arrowHead(d, cx0, r.y - 1, 0, 1, { color: INK });
      }
    }
    for (const c of curves) {
      if (c.self || !c.t.label) continue;
      labelBox(d, c.t.label, c.lab.x, c.lab.y, { maxW: 110 });
    }
  };
  return { w: W, h: H, draw };
}

// ------------------------------------------------------------------ ER
function cardInfo(c) {
  const s = String(c ?? '1');
  const many = /n|\*|many/i.test(s), zero = /^0/.test(s) || /0\.\./.test(s);
  const one = !many;
  const bar = !zero && (one || /1\.\./.test(s));
  return { many, zero, bar, two: one && !zero };
}
function drawCard(d, x, y, dx, dy, card, col = LINE) {
  const c = cardInfo(card), px = -dy, py = dx;
  d.save().lineWidth(1).strokeColor(col);
  const P = (t, s = 0) => [x + dx * t + px * s, y + dy * t + py * s];
  const seg = (a, b) => d.moveTo(...a).lineTo(...b).stroke();
  if (c.many) { const ap = P(10); seg(ap, P(0, 5)); seg(ap, P(0, -5)); seg(ap, P(0, 0)); if (c.zero) d.circle(...P(15), 3).fillAndStroke('#FFFFFF', col); else if (c.bar) seg(P(13, 4), P(13, -4)); }
  else { seg(P(6, 4.5), P(6, -4.5)); if (c.zero) d.circle(...P(12), 3).fillAndStroke('#FFFFFF', col); else seg(P(10, 4.5), P(10, -4.5)); }
  d.restore();
}

function sceneER(doc, spec, W) {
  const ents = spec.entities.map((e) => ({ ...e, columns: (e.columns || []).map((c) => (typeof c === 'string' ? { name: c } : c)) }));
  const byName = new Map(ents.map((e) => [e.name, e]));
  const cols = Math.max(1, Math.min(spec.columns || Math.floor((W + 40) / 190), ents.length));
  const colGap = cols > 1 ? 44 : 0, rowGap = 40, topPad = 18;
  const ew = (W - (cols - 1) * colGap) / cols;
  const ROW = 11.5, HEAD = 17;
  ents.forEach((e, i) => {
    e.c = e.pos ? e.pos[0] : i % cols; e.r = e.pos ? e.pos[1] : Math.floor(i / cols);
    e.h = HEAD + Math.max(e.columns.length, 1) * ROW + 4;
  });
  const rowsN = Math.max(...ents.map((e) => e.r)) + 1;
  const rowH = new Array(rowsN).fill(0);
  ents.forEach((e) => { rowH[e.r] = Math.max(rowH[e.r], e.h); });
  const rowY = []; let ay = topPad; for (let r = 0; r < rowsN; r++) { rowY.push(ay); ay += rowH[r] + rowGap; }
  const H = ay - rowGap + 8;
  ents.forEach((e, i) => { e.x = e.c * (ew + colGap); e.y = rowY[e.r]; e.w = ew; e.color = namedColor(e.color, PAL[i % PAL.length]); });
  // ports
  const rels = (spec.relations || []).filter((r) => byName.has(r.from) && byName.has(r.to));
  const ports = new Map();
  const add = (n, side, ref, other) => { const k = n + '|' + side; if (!ports.has(k)) ports.set(k, []); ports.get(k).push({ ref, other }); };
  const plan = rels.map((r, i) => {
    const u = byName.get(r.from), v = byName.get(r.to);
    let kind, su, sv;
    if (u === v) { kind = 'self'; su = sv = 'R'; }
    else if (u.r === v.r && Math.abs(u.c - v.c) > 1) { kind = 'top'; su = sv = 'T'; }
    else if (u.c !== v.c) { kind = 'h'; su = v.c > u.c ? 'R' : 'L'; sv = v.c > u.c ? 'L' : 'R'; }
    else { kind = 'v'; su = v.r > u.r ? 'B' : 'T'; sv = v.r > u.r ? 'T' : 'B'; }
    add(u.name, su, i + 'u', (v.x + v.w / 2) + (v.y + v.h / 2) * 0.01);
    add(v.name, sv, i + 'v', (u.x + u.w / 2) + (u.y + u.h / 2) * 0.01);
    const gkey = kind === 'h' ? (Math.abs(u.c - v.c) === 1 ? 'c' + Math.min(u.c, v.c) : 'c' + u.c + (v.c > u.c ? '+' : '-')) : kind === 'v' ? (Math.abs(u.r - v.r) === 1 ? 'r' + Math.min(u.r, v.r) : 'r' + u.r + (v.r > u.r ? '+' : '-')) : 't' + Math.min(u.r, v.r);
    return { r, u, v, kind, su, sv, i, gkey, lane: 0 };
  });
  { const groups = new Map(); for (const p of plan) { if (!groups.has(p.gkey)) groups.set(p.gkey, []); groups.get(p.gkey).push(p); } for (const g of groups.values()) g.forEach((p, k) => { p.lane = (k - (g.length - 1) / 2) * 6; }); }
  const pp = new Map();
  for (const [k, list] of ports) {
    const [name, side] = k.split('|'); const e = byName.get(name);
    list.sort((a, b) => a.other - b.other);
    list.forEach((it, j) => {
      const f = (j + 1) / (list.length + 1);
      pp.set(it.ref, side === 'L' || side === 'R' ? e.y + HEAD + 2 + f * (e.h - HEAD - 4) : e.x + 14 + f * (e.w - 28));
    });
  }
  const sidePt = (e, side, along) => (side === 'L' ? { x: e.x, y: along } : side === 'R' ? { x: e.x + e.w, y: along } : side === 'T' ? { x: along, y: e.y } : { x: along, y: e.y + e.h });
  const dirOf = { L: [-1, 0], R: [1, 0], T: [0, -1], B: [0, 1] };
  const routes = plan.map((p) => {
    const a = pp.get(p.i + 'u'), b = pp.get(p.i + 'v');
    const P0 = sidePt(p.u, p.su, a), P1 = sidePt(p.v, p.sv, b);
    let pts;
    if (p.kind === 'self') { const x = p.u.x + p.u.w; pts = [P0, { x: x + 22, y: P0.y }, { x: x + 22, y: P1.y + 12 }, { x, y: P1.y + 12 }]; P1.y += 12; pts[3] = { x, y: P1.y }; }
    else if (p.kind === 'top') { const cy = Math.min(p.u.y, p.v.y) - 9 - Math.abs(p.lane); pts = [P0, { x: P0.x, y: cy }, { x: P1.x, y: cy }, P1]; }
    else if (p.kind === 'h') {
      const adj = Math.abs(p.u.c - p.v.c) === 1;
      const mx = adj ? (P0.x + P1.x) / 2 + p.lane : P0.x + (p.v.c > p.u.c ? 1 : -1) * (colGap / 2 + p.lane);
      pts = [P0, { x: mx, y: P0.y }, { x: mx, y: P1.y }, P1];
    } else {
      const adj = Math.abs(p.u.r - p.v.r) === 1;
      const my = adj ? (P0.y + P1.y) / 2 + p.lane : P0.y + (p.v.r > p.u.r ? 1 : -1) * (rowGap / 2);
      pts = [P0, { x: P0.x, y: my }, { x: P1.x, y: my }, P1];
    }
    return pts;
  });
  const draw = (d) => {
    plan.forEach((p, i) => {
      const pts = routes[i];
      polyline(d, pts, { color: LINE, width: 1, dash: p.r.identifying === false });
      const du = dirOf[p.su], dv = dirOf[p.sv];
      drawCard(d, pts[0].x, pts[0].y, du[0], du[1], p.r.fromCard ?? '1');
      drawCard(d, pts[pts.length - 1].x, pts[pts.length - 1].y, dv[0], dv[1], p.r.toCard ?? 'N');
    });
    for (const e of ents) {
      rrect(d, e.x, e.y, e.w, e.h, 3, '#FFFFFF', e.color, 1.1);
      d.save().roundedRect(e.x, e.y, e.w, HEAD, 3).fill(e.color).restore();
      d.save().rect(e.x, e.y + HEAD - 4, e.w, 4).fill(e.color).restore();
      const hf = fitText(d, e.name, { w: e.w - 8, h: HEAD - 3, size: 8.5, min: 6, font: 'sans-b' });
      drawFit(d, hf, e.x + 4, e.y, e.w - 8, HEAD, { color: '#FFFFFF' });
      e.columns.forEach((c, i) => {
        const y = e.y + HEAD + 2 + i * ROW;
        if (i % 2 === 1) d.save().rect(e.x + 0.6, y - 0.5, e.w - 1.2, ROW).fill('#F5F7FA').restore();
        const badge = c.pk ? 'PK' : c.fk ? 'FK' : '';
        const bw = 12;
        if (badge) drawStr(d, badge, e.x + 3, y + 1.6, { font: 'sans-b', size: 5.5, color: c.pk ? '#B35C00' : '#2E78C7' });
        const typ = c.type ? safe(d, 'sans', c.type) : '';
        let size = 7.5; const maxW = e.w - 8 - bw;
        let nm = safe(d, c.pk ? 'sans-b' : 'sans', c.name);
        while (size > 5.5 && tw(d, 'sans', size, nm) + (typ ? tw(d, 'sans', size - 0.5, typ) + 6 : 0) > maxW) size -= 0.5;
        let tsz = size - 0.5, tW = typ ? tw(d, 'sans', tsz, typ) : 0;
        if (tw(d, c.pk ? 'sans-b' : 'sans', size, nm) + tW + 6 > maxW) {
          while (nm.length > 2 && tw(d, 'sans', size, nm + '...') + tW + 6 > maxW) nm = nm.slice(0, -1);
          if (nm !== safe(d, 'sans', c.name)) nm += '...';
          if (tw(d, 'sans', size, nm) + tW + 6 > maxW) { tW = 0; }
        }
        drawStr(d, nm, e.x + 3 + bw, y + 1, { font: c.pk ? 'sans-b' : 'sans', size, color: INK });
        if (typ && tW) drawStr(d, typ, e.x + e.w - 4 - tW, y + 1.3, { font: 'sans', size: tsz, color: GRAY });
      });
    }
    plan.forEach((p, i) => { if (p.r.label) { const m = longestSeg(routes[i]); labelBox(d, p.r.label, m.x + (m.vertical ? 4 : 0), m.y, { maxW: 100 }); } });
  };
  return { w: W, h: H, draw };
}

// ------------------------------------------------------------------ mind map
function sceneMind(doc, spec, W0) {
  const branches = (spec.branches || []).map((b) => (typeof b === 'string' ? { label: b } : b));
  const norm = (n, d, col) => ({ label: typeof n === 'string' ? n : n.label, color: col, depth: d, kids: (typeof n === 'string' ? [] : n.children || []).map((c) => norm(c, d + 1, col)) });
  const tree = branches.map((b, i) => norm(b, 1, namedColor(b.color, PAL[i % PAL.length])));
  const maxDepth = (n) => 1 + Math.max(0, ...n.kids.map(maxDepth));
  const D = Math.max(1, ...tree.map(maxDepth));
  const sizes = [0, 9, 8, 7.5, 7, 6.5];
  const caps = [0, 80, 92, 84, 80, 76];
  const fontOf = (d) => (d === 1 ? 'sans-b' : 'sans');
  const all = []; const walk = (n) => { all.push(n); n.kids.forEach(walk); }; tree.forEach(walk);
  const want = [0];
  for (let l = 1; l <= D; l++) {
    const ns = all.filter((n) => n.depth === l);
    const f = fontOf(l), sz = sizes[Math.min(l, 5)];
    let nat = 0, minw = 0;
    for (const n of ns) {
      const s = safe(doc, f, n.label);
      nat = Math.max(nat, tw(doc, f, sz, s) + 12);
      for (const w of s.split(/\s+/)) if (w) minw = Math.max(minw, tw(doc, f, sz, w) + 12);
    }
    want.push(Math.min(Math.max(Math.min(nat, caps[Math.min(l, 5)]), minw), 140));
  }
  const colGap = 14;
  const cf0 = fitText(doc, spec.center || 'Topic', { w: 96, h: 4 * 10.5 * 1.2, size: 10.5, min: 7, font: 'sans-b' });
  const cW = 108;
  const sideNeed = want.reduce((a, b) => a + b, 0) + colGap * D;
  const W = Math.max(W0, 2 * sideNeed + cW + 12);
  const meas = (n) => {
    n.w = want[n.depth];
    n.fit = fitText(doc, n.label, { w: n.w - 8, h: 3 * 9 * 1.2, size: sizes[Math.min(n.depth, 5)], min: 6, font: fontOf(n.depth) });
    n.h = n.fit.h + 8;
    n.kids.forEach(meas);
    n.sub = Math.max(n.h + 6, n.kids.reduce((a, k) => a + k.sub, 0));
  };
  tree.forEach(meas);
  const sh = (arr) => arr.reduce((a, b) => a + b.sub, 0);
  const total = sh(tree);
  let acc = 0; const right = [], left = [];
  tree.forEach((b) => { if (acc + b.sub / 2 <= total / 2 + 1 && right.length < Math.max(1, Math.ceil(tree.length / 2))) { right.push(b); acc += b.sub; } else left.push(b); });
  const H = Math.max(sh(right), sh(left), 80) + 16;
  const cx = W / 2, cy = H / 2;
  const cf = cf0, cH = Math.max(36, cf.h + 14);
  const links = [], nodes = [];
  const place = (arr, dirn) => {
    let y = cy - sh(arr) / 2;
    const rec = (n, x, yTop, parentPt) => {
      const yc = yTop + n.sub / 2;
      const nx = dirn > 0 ? x : x - n.w;
      n.x = nx; n.y = yc - n.h / 2;
      nodes.push(n);
      links.push({ from: parentPt, to: { x: dirn > 0 ? nx : nx + n.w, y: yc }, color: n.color, depth: n.depth });
      let yy = yTop + (n.sub - n.kids.reduce((a, k) => a + k.sub, 0)) / 2;
      for (const k of n.kids) { rec(k, dirn > 0 ? nx + n.w + colGap : nx - colGap, yy, { x: dirn > 0 ? nx + n.w : nx, y: yc }); yy += k.sub; }
    };
    for (const b of arr) { rec(b, dirn > 0 ? cx + cW / 2 + colGap : cx - cW / 2 - colGap, y, { x: dirn > 0 ? cx + cW / 2 : cx - cW / 2, y: cy }); y += b.sub; }
  };
  place(right, 1); place(left, -1);
  const draw = (d) => {
    for (const l of links) {
      const mx = (l.from.x + l.to.x) / 2;
      d.save().lineWidth(Math.max(0.9, 2.6 - l.depth * 0.5)).strokeColor(l.color).moveTo(l.from.x, l.from.y).bezierCurveTo(mx, l.from.y, mx, l.to.y, l.to.x, l.to.y).stroke().restore();
    }
    for (const n of nodes) {
      if (n.depth === 1) rrect(d, n.x, n.y, n.w, n.h, n.h / 2.4, n.color, null);
      else rrect(d, n.x, n.y, n.w, n.h, 4, tint(n.color, 0.88), n.color, 0.9);
      drawFit(d, n.fit, n.x + 2, n.y, n.w - 4, n.h, { color: n.depth === 1 ? '#FFFFFF' : shade(n.color, 0.5) });
    }
    rrect(d, cx - cW / 2, cy - cH / 2, cW, cH, 10, '#1F3A5F', null);
    drawFit(d, cf, cx - cW / 2 + 4, cy - cH / 2, cW - 8, cH, { color: '#FFFFFF' });
  };
  return { w: W, h: H, draw };
}

// ------------------------------------------------------------------ charts
function niceScale(max, n = 5) {
  if (!(max > 0)) return { step: 0.2, max: 1 };
  const raw = max / n, mag = 10 ** Math.floor(Math.log10(raw)), nm = raw / mag;
  const step = (nm <= 1 ? 1 : nm <= 2 ? 2 : nm <= 2.5 ? 2.5 : nm <= 5 ? 5 : 10) * mag;
  return { step, max: Math.ceil(max / step - 1e-9) * step };
}
const fmtNum = (v) => (Number.isInteger(v) ? v.toLocaleString('en-US') : String(+v.toFixed(2)));

function sceneBar(doc, spec, W, horizontal) {
  let cats, series;
  if (spec.data) { cats = spec.data.map((x) => x.label); series = [{ name: spec.seriesName || '', values: spec.data.map((x) => x.value), colors: spec.data.map((x, i) => namedColor(x.color, spec.data.length > 1 && !spec.singleColor ? PAL[0] : PAL[0])) }]; }
  else { cats = spec.categories; series = spec.series.map((s, i) => ({ ...s, colors: cats.map(() => namedColor(s.color, PAL[i % PAL.length])) })); }
  const multi = series.length > 1;
  const unit = spec.unit || '';
  const maxV = Math.max(0, ...series.flatMap((s) => s.values));
  const sc = niceScale(spec.max ?? maxV);
  let y0 = 0;
  const title = null; void title;
  const legendH = multi ? 16 : 0;
  const parts = [];
  if (!horizontal) {
    const H = spec.height || 230;
    const yLabW = Math.max(...Array.from({ length: Math.round(sc.max / sc.step) + 1 }, (_, i) => tw(doc, 'sans', 7, fmtNum(i * sc.step)))) + 8;
    const left = yLabW + 4, right = 6, plotW = W - left - right;
    const gW = plotW / cats.length;
    // category label layout
    let rot = false, lab = [];
    const lw = gW - 3;
    lab = cats.map((c) => wrapText(doc, String(c), 'sans', 7.5, lw));
    if (lab.some((l) => l.length > 3) || cats.some((c) => String(c).split(/\s+/).some((w) => tw(doc, 'sans', 7.5, safe(doc, 'sans', w)) > lw + 0.5))) rot = true;
    const maxLabW = Math.max(...cats.map((c) => tw(doc, 'sans', 7.5, safe(doc, 'sans', String(c)))));
    const catH = rot ? Math.min(maxLabW, 90) * 0.72 + 8 : Math.max(...lab.map((l) => l.length)) * 9 + 6;
    const top = 12 + legendH, bottom = catH + (spec.xLabel ? 12 : 0);
    const ph = H - top - bottom;
    const yOf = (v) => top + ph - (v / sc.max) * ph;
    const draw = (d) => {
      if (multi) series.forEach((s, i) => { const lx = left + i * 110; d.save().rect(lx, 2, 8, 8).fill(namedColor(s.color, PAL[i % PAL.length])).restore(); drawStr(d, s.name, lx + 12, 2, { size: 7.5 }); });
      for (let v = 0; v <= sc.max + 1e-9; v += sc.step) {
        const yy = yOf(v);
        d.save().lineWidth(0.5).strokeColor(v === 0 ? '#7A8795' : '#E1E6EB').moveTo(left, yy).lineTo(left + plotW, yy).stroke().restore();
        drawStr(d, fmtNum(v), 0, yy - 4, { size: 7, color: GRAY, align: 'right', w: yLabW });
      }
      if (unit) drawStr(d, unit, left, top - 11, { size: 7, color: GRAY, font: 'sans-i' });
      cats.forEach((c, ci) => {
        const gx = left + ci * gW, k = series.length, bw = (gW * 0.72) / k, bx0 = gx + gW * 0.14;
        series.forEach((s, si) => {
          const v = s.values[ci] ?? 0, yy = yOf(v), col = multi ? namedColor(s.color, PAL[si % PAL.length]) : s.colors[ci];
          d.save().rect(bx0 + si * bw + 0.5, yy, bw - 1, Math.max(0, yOf(0) - yy)).fill(col).restore();
          const txt = fmtNum(v); let fs = 7;
          while (fs > 5 && tw(doc, 'sans', fs, txt) > gW - 1) fs -= 0.5;
          if (tw(doc, 'sans', fs, txt) <= (k > 1 ? Math.max(bw + 8, gW / k) : gW) + 1) drawStr(d, txt, bx0 + si * bw - 4, yy - fs - 1.5, { size: fs, w: bw + 8, align: 'center', color: INK });
        });
        const ly = top + ph + 4;
        if (!rot) lab[ci].forEach((l, li) => drawStr(d, l, gx + 1.5, ly + li * 9, { size: 7.5, w: gW - 3, align: 'center', color: INK }));
        else {
          d.save().translate(gx + gW / 2 + 3, ly + 1).rotate(40);
          drawStr(d, safe(d, 'sans', String(c)).length > 28 ? safe(d, 'sans', String(c)).slice(0, 27) + '...' : String(c), 0, -4, { size: 7.5, color: INK });
          d.restore();
        }
      });
      if (spec.xLabel) drawStr(d, spec.xLabel, left, H - 10, { size: 7.5, w: plotW, align: 'center', color: GRAY });
    };
    return { w: W, h: H, draw };
  }
  // horizontal
  const labW = Math.min(W * 0.34, Math.max(...cats.map((c) => tw(doc, 'sans', 8, safe(doc, 'sans', String(c))))) + 8);
  const left = labW + 6, right = 40, plotW = W - left - right;
  const k = series.length, barH = k > 1 ? 9 : 13, rowH = k * barH + 9;
  const top = legendH + (unit ? 12 : 0) + 2, axisH = 14;
  const H = top + cats.length * rowH + axisH;
  const xOf = (v) => left + (v / sc.max) * plotW;
  const draw = (d) => {
    if (multi) series.forEach((s, i) => { const lx = left + i * 110; d.save().rect(lx, 2, 8, 8).fill(namedColor(s.color, PAL[i % PAL.length])).restore(); drawStr(d, s.name, lx + 12, 2, { size: 7.5 }); });
    if (unit) drawStr(d, unit, left, top - 12, { size: 7, color: GRAY, font: 'sans-i' });
    for (let v = 0; v <= sc.max + 1e-9; v += sc.step) {
      const xx = xOf(v);
      d.save().lineWidth(0.5).strokeColor(v === 0 ? '#7A8795' : '#E1E6EB').moveTo(xx, top).lineTo(xx, top + cats.length * rowH).stroke().restore();
      drawStr(d, fmtNum(v), xx - 20, top + cats.length * rowH + 3, { size: 7, color: GRAY, w: 40, align: 'center' });
    }
    cats.forEach((c, ci) => {
      const ry = top + ci * rowH;
      const f = fitText(d, String(c), { w: labW, h: rowH - 2, size: 8, min: 6, font: 'sans' });
      drawFit(d, f, 0, ry, labW, rowH - 2, { align: 'right', color: INK });
      series.forEach((s, si) => {
        const v = s.values[ci] ?? 0, col = multi ? namedColor(s.color, PAL[si % PAL.length]) : s.colors[ci];
        const by = ry + 3 + si * barH;
        d.save().rect(left, by, Math.max(0, xOf(v) - left), barH - 1.5).fill(col).restore();
        drawStr(d, fmtNum(v), xOf(v) + 3, by + (barH - 1.5) / 2 - 3.5, { size: 7, color: INK });
      });
    });
  };
  void y0; void parts;
  return { w: W, h: H, draw };
}

// ------------------------------------------------------------------ dispatch + placement
export const DIAGRAM_TYPES = ['boxes-and-arrows', 'sequence', 'state', 'er', 'mindmap', 'bar', 'hbar'];

export function buildScene(doc, spec, W) {
  let sc;
  switch (spec.type) {
    case 'boxes-and-arrows': case 'boxes': case 'architecture': case 'flow': sc = sceneBoxes(doc, spec, W); break;
    case 'sequence': sc = sceneSequence(doc, spec, W); break;
    case 'state': case 'state-machine': sc = sceneState(doc, spec, W); break;
    case 'er': sc = sceneER(doc, spec, W); break;
    case 'mindmap': case 'mind-map': sc = sceneMind(doc, spec, W); break;
    case 'bar': case 'column': sc = sceneBar(doc, spec, W, spec.orientation === 'horizontal'); break;
    case 'hbar': sc = sceneBar(doc, spec, W, true); break;
    default: throw new Error('Unknown diagram type: ' + spec.type);
  }
  if (spec.title) {
    const inner = sc, off = 16;
    sc = { ...inner, h: inner.h + off, draw: (d) => { drawStr(d, spec.title, 0, 0, { font: 'sans-b', size: 9.5, color: '#1F3A5F', w: inner.w, align: 'center' }); d.save().translate(0, off); inner.draw(d); d.restore(); } };
    if (inner.cuts) { sc.cuts = inner.cuts.map((c) => c + off); sc.headerH = inner.headerH + off; sc.drawHeader = (d) => { drawStr(d, spec.title, 0, 0, { font: 'sans-b', size: 9.5, color: '#1F3A5F', w: inner.w, align: 'center' }); d.save().translate(0, off); inner.drawHeader(d); d.restore(); }; }
  }
  return sc;
}

/**
 * Place a diagram on the page. R is the Renderer (needs doc,x,y,w,top,bottom,dry,ensure,newPage,captionLines,caption).
 * Returns the vertical space consumed on the last page (scaled scene + caption).
 */
export function renderDiagram(R, spec) {
  const doc = R.doc, W = R.w;
  const scene = buildScene(doc, spec, W);
  const capLines = spec.caption ? R.captionLines(spec.caption) : [];
  const capH = capLines.length ? capLines.length * 12 + 10 : 0;
  const pageH = R.bottom - R.top;
  let s = Math.min(1, W / scene.w);
  const maxSceneH = pageH - capH - 8;
  const place = (x, y, sc, fn) => { if (R.dry) return; doc.save(); doc.translate(x, y); doc.scale(sc); fn(); doc.restore(); };
  let consumed = 0;
  if (scene.h * s <= maxSceneH || !scene.cuts) {
    if (scene.h * s > maxSceneH) s = maxSceneH / scene.h; // scale-to-fit single page
    R.ensure(scene.h * s + capH + 8);
    place(R.x + (W - scene.w * s) / 2, R.y, s, () => scene.draw(doc));
    R.y += scene.h * s + 8; consumed = scene.h * s + 8;
  } else {
    // cut across pages (sequence diagrams); repeat the participant header on continuation pages
    const remaining = R.bottom - R.y;
    if (scene.h * s > remaining && remaining < 220) R.newPage();
    let y0 = 0, first = true;
    while (y0 < scene.h - 0.5) {
      const hdr = first ? 0 : scene.headerH;
      if (R.bottom - R.y < 120) R.newPage();
      const availS = (R.bottom - R.y) / s - hdr;
      let y1;
      if (y0 + availS >= scene.h + capH / s) y1 = scene.h;
      else {
        y1 = Math.max(0, ...scene.cuts.filter((c) => c > y0 + 20 && c <= y0 + availS));
        if (y1 <= y0) y1 = Math.min(scene.h, y0 + availS);
      }
      const ys = R.y;
      place(R.x + (W - scene.w * s) / 2, ys, s, () => {
        if (!first) scene.drawHeader(doc);
        doc.save(); doc.translate(0, hdr - y0); doc.rect(-2, y0, scene.w + 4, y1 - y0).clip(); scene.draw(doc); doc.restore();
      });
      R.y += (hdr + (y1 - y0)) * s + 6; consumed = (hdr + (y1 - y0)) * s + 6;
      y0 = y1; first = false;
      if (y0 < scene.h - 0.5) R.newPage();
    }
  }
  if (spec.caption) { R.caption(spec.caption); consumed += capH; }
  else R.y += 6;
  return consumed;
}
