// Dev helper: node pdf1/page-stats.mjs <pdf> [threshold]  -> lists pages whose body text (between header and footer) is short.
import fs from 'node:fs';
const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
const [file, thr = '100'] = process.argv.slice(2);
const doc = await pdfjs.getDocument({ data: new Uint8Array(fs.readFileSync(file)), verbosity: 0 }).promise;
const out = [];
for (let p = 1; p <= doc.numPages; p++) {
  const page = await doc.getPage(p);
  const vp = page.getViewport({ scale: 1 });
  const tc = await page.getTextContent();
  let body = '';
  let header = '';
  for (const it of tc.items) {
    if (!('str' in it)) continue;
    const yTop = vp.height - it.transform[5];
    if (yTop > 52 && yTop < vp.height - 66) body += it.str + ' ';
    else if (yTop <= 52) header += it.str + ' ';
  }
  body = body.trim();
  if (body.length < Number(thr)) out.push(`${p} [${body.length}] ${body.slice(0, 80)}`);
}
console.log(out.join('\n'));
