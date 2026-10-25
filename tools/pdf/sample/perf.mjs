// Performance check: builds a ~400+ page document and reports time and peak RSS.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildDocument } from '../lib/render.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const chapters = [];
for (let c = 1; c <= 50; c++) {
  let t = `# Chapter ${c}\n\n`;
  for (let s = 1; s <= 4; s++) {
    t += `## Section ${c}.${s}\n\n`;
    t += Array.from({ length: 5 }, (_, i) => `Paragraph ${i} of section ${c}.${s}: the quick brown fox jumps over the lazy dog with **bold**, *italic* and \`code\` spans. `.repeat(6)).join('\n\n') + '\n\n';
    t += '- item one\n  - nested item\n- item two\n\n';
    t += '```java\n' + Array.from({ length: 30 }, (_, i) => `    int value${i} = compute(${i}, "argument-${c}-${s}"); // comment ${i}`).join('\n') + '\n```\n\n';
    t += '| a | b | c |\n|---|---|---|\n' + Array.from({ length: 12 }, (_, i) => `| r${i} | value ${i} | some longer text for row ${i} |`).join('\n') + '\n\n';
  }
  chapters.push({ type: 'markdown', text: t });
}
const t0 = Date.now();
let peak = 0;
const timer = setInterval(() => { peak = Math.max(peak, process.memoryUsage().rss); }, 100);
const res = await buildDocument({ title: 'Performance document', subtitle: 'synthetic', version: 'perf', outFile: path.join(here, '..', 'out', 'perf.pdf'), parts: chapters });
clearInterval(timer);
peak = Math.max(peak, process.memoryUsage().rss);
console.log(JSON.stringify({ pages: res.pages, seconds: ((Date.now() - t0) / 1000).toFixed(1), peakRssMiB: Math.round(peak / 1048576) }));
