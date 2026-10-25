// Scratch renderer for iterating on one diagram spec: node pdf2/scratch.mjs <spec.json> <out.pdf>
import fs from 'node:fs';
import { buildDocument } from '../lib/render.mjs';
const spec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const r = await buildDocument({ title: 'scratch', outFile: process.argv[3], toc: false, parts: [{ type: 'markdown', text: '# Scratch\n' }, { type: 'diagram', spec }] });
console.log(r.pages, r.warnings);
