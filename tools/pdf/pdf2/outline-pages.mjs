// Debug helper: node pdf2/outline-pages.mjs <pdf> <regex>   prints the page of every outline entry matching the regex
import fs from 'node:fs';
const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
const doc = await pdfjs.getDocument({ data: new Uint8Array(fs.readFileSync(process.argv[2])), verbosity: 0, disableFontFace: true }).promise;
const re = new RegExp(process.argv[3], 'i');
const walk = async (items) => { for (const it of items || []) { if (re.test(it.title)) { const d = typeof it.dest === 'string' ? await doc.getDestination(it.dest) : it.dest; console.log((await doc.getPageIndex(d[0])) + 1, it.title.slice(0, 90)); } await walk(it.items); } };
await walk(await doc.getOutline());
