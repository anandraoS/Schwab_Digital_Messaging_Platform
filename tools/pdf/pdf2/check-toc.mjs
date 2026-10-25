// Debug helper: prints h1 TOC entries whose printed page number differs from the outline target page.
import fs from 'node:fs';
const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
const doc = await pdfjs.getDocument({ data: new Uint8Array(fs.readFileSync(process.argv[2])), verbosity: 0, disableFontFace: true }).promise;
const texts = [];
for (let p = 1; p <= Math.min(12, doc.numPages); p++) { const tc = await (await doc.getPage(p)).getTextContent(); texts.push(tc.items.map((i) => i.str + (i.hasEOL ? '\n' : ' ')).join('')); }
const outline = await doc.getOutline();
for (const it of outline) {
  if (it.title === 'Contents') continue;
  const dest = typeof it.dest === 'string' ? await doc.getDestination(it.dest) : it.dest;
  const idx = (await doc.getPageIndex(dest[0])) + 1;
  let printed = null;
  for (let p = 1; p < texts.length; p++) { const flat = texts[p].replace(/\n/g, ' '); const k = flat.indexOf(it.title.slice(0, 30)); if (k >= 0) { const m = /(\d+)\s/.exec(flat.slice(k + it.title.length, k + it.title.length + 40)); printed = m ? +m[1] : null; break; } }
  if (printed !== null && printed !== idx) console.log('MISMATCH', it.title, 'outline', idx, 'toc', printed);
}
