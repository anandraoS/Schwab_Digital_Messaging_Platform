// Dev helper: node pdf1/try-diagram.mjs key1 key2 ...  -> builds pdf1/out/try.pdf with just those diagrams (one per h1 page).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildDocument } from '../lib/render.mjs';
import { DIAGRAMS } from './diagrams.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const keys = process.argv.slice(2);
const outDir = path.join(here, 'out');
fs.mkdirSync(outDir, { recursive: true });
const parts = keys.map((k) => ({ type: 'markdown', text: `# ${k}\n\n` + '```diagram ' + JSON.stringify(DIAGRAMS[k]) + '\n```\n' }));
const res = await buildDocument({ title: 'try', subtitle: 'diagram trial', outFile: path.join(outDir, 'try.pdf'), toc: false, parts, baseDir: here });
console.log(res.pages, res.warnings);
