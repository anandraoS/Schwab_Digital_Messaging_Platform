// Dev helper: node pdf1/figure-pages.mjs <pdf>  -> "page: Figure N. caption start" for every figure caption.
import fs from 'node:fs';
const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
const doc = await pdfjs.getDocument({ data: new Uint8Array(fs.readFileSync(process.argv[2])), verbosity: 0 }).promise;
const out = [];
for (let p = 1; p <= doc.numPages; p++) {
  const tc = await (await doc.getPage(p)).getTextContent();
  const text = tc.items.map((i) => i.str).join(' ');
  for (const m of text.matchAll(/Figure (\d+)\.\s+(.{0,70})/g)) out.push(`${p}: Figure ${m[1]}. ${m[2]}`);
}
console.log(out.join('\n'));
