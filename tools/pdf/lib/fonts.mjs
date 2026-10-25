// Font discovery, registration and glyph-safe text sanitising.
// Fonts are TrueType files found on the machine (Segoe UI / Consolas / Arial on Windows,
// DejaVu on Linux). They are embedded (subset) by pdfkit. If nothing is found we fall back
// to the PDF standard fonts (NOT embedded) and the verifier will flag that.
import fs from 'node:fs';
import path from 'node:path';

const DIRS = [
  process.env.PDF_FONT_DIR,
  'C:\\Windows\\Fonts',
  '/c/Windows/Fonts',
  '/usr/share/fonts/truetype/dejavu',
  '/usr/share/fonts/dejavu',
  '/Library/Fonts',
  '/System/Library/Fonts/Supplemental',
].filter(Boolean);

const FAMILIES = {
  sans: [
    ['segoeui.ttf', 'segoeuib.ttf', 'segoeuii.ttf', 'segoeuiz.ttf'],
    ['arial.ttf', 'arialbd.ttf', 'ariali.ttf', 'arialbi.ttf'],
    ['DejaVuSans.ttf', 'DejaVuSans-Bold.ttf', 'DejaVuSans-Oblique.ttf', 'DejaVuSans-BoldOblique.ttf'],
  ],
  mono: [
    ['consola.ttf', 'consolab.ttf', 'consolai.ttf', 'consolaz.ttf'],
    ['cour.ttf', 'courbd.ttf', 'couri.ttf', 'courbi.ttf'],
    ['DejaVuSansMono.ttf', 'DejaVuSansMono-Bold.ttf', 'DejaVuSansMono-Oblique.ttf', 'DejaVuSansMono-BoldOblique.ttf'],
  ],
  foot: [
    ['Montserrat-Regular.ttf', 'Montserrat-Bold.ttf', 'Montserrat-Italic.ttf', 'Montserrat-BoldItalic.ttf'],
    ['arial.ttf', 'arialbd.ttf', 'ariali.ttf', 'arialbi.ttf'],
    ['DejaVuSans.ttf', 'DejaVuSans-Bold.ttf', 'DejaVuSans-Oblique.ttf', 'DejaVuSans-BoldOblique.ttf'],
  ],
};

function findFile(name) {
  for (const d of DIRS) {
    const p = path.join(d, name);
    if (fs.existsSync(p)) return p;
  }
  return null;
}

function resolveFamily(key) {
  for (const set of FAMILIES[key]) {
    const files = set.map(findFile);
    if (files[0]) return { regular: files[0], bold: files[1] || files[0], italic: files[2] || files[0], bi: files[3] || files[1] || files[0] };
  }
  return null;
}

/** Registers font aliases on the doc: sans, sans-b, sans-i, sans-bi, mono, mono-b, mono-i, foot, foot-i. */
export function registerFonts(doc) {
  const info = { embedded: true, families: {} };
  const std = { sans: ['Helvetica', 'Helvetica-Bold', 'Helvetica-Oblique', 'Helvetica-BoldOblique'], mono: ['Courier', 'Courier-Bold', 'Courier-Oblique', 'Courier-BoldOblique'] };
  std.foot = std.sans;
  for (const key of ['sans', 'mono', 'foot']) {
    const fam = resolveFamily(key);
    if (fam) {
      doc.registerFont(key, fam.regular);
      doc.registerFont(key + '-b', fam.bold);
      doc.registerFont(key + '-i', fam.italic);
      doc.registerFont(key + '-bi', fam.bi);
      info.families[key] = path.basename(fam.regular);
    } else {
      doc.registerFont(key, std[key][0]);
      doc.registerFont(key + '-b', std[key][1]);
      doc.registerFont(key + '-i', std[key][2]);
      doc.registerFont(key + '-bi', std[key][3]);
      info.families[key] = std[key][0] + ' (standard, not embedded)';
      info.embedded = false;
    }
  }
  return info;
}

const REPLACE = new Map(Object.entries({
  '\u21D2': '=>', '\u21D0': '<=', '\u21D4': '<=>', '\u2192': '->', '\u2190': '<-', '\u2194': '<->',
  '\u2191': '^', '\u2193': 'v', '\u2713': '[x]', '\u2714': '[x]', '\u2717': '[ ]', '\u2718': '[ ]',
  '\u2022': '*', '\u25CF': '*', '\u25CB': 'o', '\u25AA': '-', '\u25B6': '>', '\u25BC': 'v',
  '\u2018': "'", '\u2019': "'", '\u201C': '"', '\u201D': '"', '\u2013': '-', '\u2014': '--',
  '\u2026': '...', '\u00A0': ' ', '\u2009': ' ', '\u202F': ' ', '\u2003': ' ', '\u2002': ' ',
  '\u2264': '<=', '\u2265': '>=', '\u2260': '!=', '\u00D7': 'x', '\u2212': '-', '\u2248': '~',
  '\u2500': '-', '\u2502': '|', '\u250C': '+', '\u2510': '+', '\u2514': '+', '\u2518': '+',
  '\u251C': '+', '\u2524': '+', '\u252C': '+', '\u2534': '+', '\u253C': '+',
}));

const glyphCache = new Map();
function hasGlyph(doc, fontName, cp) {
  let m = glyphCache.get(fontName);
  if (!m) { m = new Map(); glyphCache.set(fontName, m); }
  let v = m.get(cp);
  if (v !== undefined) return v;
  doc.font(fontName);
  const f = doc._font && doc._font.font;
  if (f && typeof f.hasGlyphForCodePoint === 'function') v = f.hasGlyphForCodePoint(cp);
  else v = cp <= 0xFF || cp === 0x2022; // standard fonts: Latin-1 only
  m.set(cp, v);
  return v;
}

/** Make `text` safe for the given registered font name: unsupported glyphs are replaced or dropped. */
export function safe(doc, fontName, text) {
  if (text == null) return '';
  let s = String(text);
  // fast path: plain printable ASCII
  if (/^[\x20-\x7E]*$/.test(s)) return s;
  s = s.replace(/\t/g, '    ').replace(/\r/g, '');
  let out = '';
  for (const ch of s) {
    const cp = ch.codePointAt(0);
    if (cp === 10) { out += ch; continue; }
    if (cp < 32 || cp === 0x200B || cp === 0xFE0F || cp === 0xFE0E || cp === 0x200D || cp === 0xFEFF) continue;
    if (cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF && !hasGlyph(doc, fontName, cp) && !REPLACE.has(ch))) continue; // emoji / dingbats
    if (hasGlyph(doc, fontName, cp)) {
      // a few glyphs exist in the font but render poorly in some fonts; keep them
      out += ch;
    } else if (REPLACE.has(ch)) out += REPLACE.get(ch);
    else out += '?';
  }
  return out;
}

// width cache (bounded)
const widthCache = new Map();
export function textWidth(doc, fontName, size, str) {
  const k = fontName + '|' + size + '|' + str;
  let w = widthCache.get(k);
  if (w === undefined) {
    w = doc.font(fontName).fontSize(size).widthOfString(str);
    if (widthCache.size > 300000) widthCache.clear();
    widthCache.set(k, w);
  }
  return w;
}

export function ascent(doc, fontName, size) {
  doc.font(fontName).fontSize(size);
  return (doc._font.ascender / 1000) * size;
}
