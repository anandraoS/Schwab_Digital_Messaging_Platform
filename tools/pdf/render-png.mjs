#!/usr/bin/env node
// Optional visual check: node render-png.mjs <pdf> <outDir> <page> [page...] [--scale 1.4]
// Uses pdfjs-dist + @napi-rs/canvas (an optional dependency that npm installs together with pdfjs-dist).
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
let scale = 1.3; const rest = [];
for (let i = 0; i < args.length; i++) { if (args[i] === '--scale') scale = parseFloat(args[++i]); else rest.push(args[i]); }
const [pdf, outDir, ...pages] = rest;
if (!pdf || !outDir || !pages.length) { console.error('usage: node render-png.mjs <pdf> <outDir> <page>... [--scale N]'); process.exit(2); }
const { createCanvas } = await import('@napi-rs/canvas');
const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
const doc = await pdfjs.getDocument({ data: new Uint8Array(fs.readFileSync(pdf)), verbosity: 0, disableFontFace: true }).promise;
fs.mkdirSync(outDir, { recursive: true });
for (const pn of pages) {
  const p = parseInt(pn, 10);
  const page = await doc.getPage(p);
  const vp = page.getViewport({ scale });
  const canvas = createCanvas(Math.ceil(vp.width), Math.ceil(vp.height));
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#ffffff'; ctx.fillRect(0, 0, canvas.width, canvas.height);
  await page.render({ canvasContext: ctx, viewport: vp, canvas }).promise;
  const out = path.join(outDir, `page-${String(p).padStart(3, '0')}.png`);
  fs.writeFileSync(out, canvas.toBuffer('image/png'));
  console.log(out);
}
