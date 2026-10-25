# PDF engine (`tools/pdf`)

Markdown-subset to PDF renderer on `pdfkit` 0.20.2 with a vector diagram DSL, an interview Q&A renderer and a
`pdfjs-dist` 6.4.299 based verifier. Node ESM, Node 24. Pass Windows-style paths to node on this machine (`cygpath -m`).

```
npm install            # once, inside tools/pdf
npm run sample         # builds out/sample.pdf (38 pages, every feature)
npm run verify         # node verify-pdf.mjs out/sample.pdf --min-pages 12
node sample/perf.mjs   # 400+ page timing / memory check
node render-png.mjs out/sample.pdf out/png 1 5 10   # optional PNG preview (pdfjs + @napi-rs/canvas)
```

## Build a document

```js
import { buildDocument } from './lib/render.mjs';
import { loadMarkdownDir } from './lib/md.mjs';

const res = await buildDocument({
  title: 'Design Guide', subtitle: 'Messaging platform', version: 'v1.0', date: '2026-10-18', author: 'Team',
  disclaimer: 'Synthetic data only.',            // optional box on the title page (default text if omitted; '' hides)
  outFile: 'C:/abs/path/out/guide.pdf',
  toc: true, tocDepth: 3,                         // defaults
  pageSize: 'LETTER',                             // or 'A4'
  header: 'Design Guide',                         // running header text (default = title); right side shows current h1
  baseDir: 'C:/abs/docs',                         // resolves relative diagram files / images
  parts: [
    { type: 'markdown', file: 'C:/abs/docs/01-intro.md' },           // or { type:'markdown', text:'# Hi' }
    { type: 'markdown', text: 'body', title: 'Auto h1 title' },       // optional title => inserts a heading first
    ...loadMarkdownDir('C:/abs/docs/design', { order: ['01-a', '02-b'] }),   // ordered dir (see below)
    { type: 'interview', files: ['C:/abs/docs/interview/q1.md', 'C:/abs/docs/interview/q2.md'] },
    { type: 'diagram', spec: { type: 'bar', data: [{ label: 'a', value: 1 }] }, caption: 'Optional caption' },
    { type: 'pagebreak' },
  ],
});
// res = { outFile, pages, ms, warnings: string[], fonts, stats }   (warnings: missing images, bad diagram JSON, ...)
```

Two passes: pass 1 renders to a null stream to learn heading page numbers and the page count; pass 2 streams to the file
(`doc.pipe(fs.createWriteStream)`), so memory stays bounded (408 pages: ~15 s, ~257 MiB peak RSS measured).

### Mandatory footer
`lib/footer.mjs` exports `FOOTER_LINES` (the exact two Cotiviti lines). It is the single constant used by the renderer
(drawn on every page, small gray, with "Page n of N") and by the verifier. Override per build with `footerLines: [...]`.

## Markdown subset (`lib/md.mjs`, `parseMarkdown(text, {baseDir})`)
- Headings `#`..`####` (h1 starts a new page; h1-h3 go to the TOC, all go to the PDF outline). Every heading gets an anchor
  automatically: `[text](#heading-slug)` links to a heading (github-style slug).
- Inline: `**bold**`, `*italic*`, `***both***`, `` `code` ``, `[text](url)`, bare URLs, `<br>`, hard break (two trailing spaces).
- Lists: `-`, `*`, `+`, `1.`; any nesting by indentation; task items `- [x]`; fenced code inside an item (indented).
- Fenced code (``` or ~~~, optional language label), tables (pipe tables with `:--`, `:-:`, `--:` alignment), blockquotes,
  `---` rules, `<!-- pagebreak -->` or `\pagebreak`.
- Callouts: `> [!NOTE]`, `> [!WARNING]`, `> [!TIP] optional title`, `> [!IMPORTANT]` or `:::note Title` ... `:::`
  (kinds: note, warning, tip, important). Callouts and quotes may contain any block and split across pages.
- Figures: `![caption](image.png)` (png/jpg embedded; missing file => placeholder + warning). Diagrams have captions too.
- Diagrams in markdown: a fence tagged `diagram`:
  ````
  ```diagram {"type":"sequence","participants":["A","B"],"steps":[{"from":"A","to":"B","text":"hi"}],"caption":"Figure text"}
  ```
  ```diagram:relative/path/spec.json
  ```
  ```diagram
  { ...multi-line JSON body... }
  ```
  ````
  Invalid JSON never aborts the build: a warning callout is rendered and `warnings[]` is populated.

### Directory loading
```js
loadMarkdownDir(dir, { order?: ['01-a', '02-b.md'], strict?: false, type?: 'markdown'|'interview' })  // => parts[]
```
Order = `opts.order`, else `_order.txt` (one file per line, `#` comments), else natural filename sort. Unlisted files are
appended unless `strict`. `README.md` is skipped. With `type:'interview'` returns one interview part with all files.

## Diagram specs (`lib/diagrams.mjs`)
All diagrams: optional `title`, `caption` (auto-numbered "Figure N."); scaled to the text width; moved to a new page if they
do not fit; text is measured, wrapped, shrunk (never overflows a shape; last resort ellipsis). Colors are `#rrggbb` or
`blue|green|orange|purple|red|teal|brown|gray`.

| `type` | Spec |
|---|---|
| `boxes-and-arrows` | `{direction:'TB'|'LR', lanes:[{id,label,color,nodes:[{id,label,sub?,color?,col?}]}], edges:[{from,to,label?,style?:'dashed',color?,bidirectional?}]}`. Lanes are rows (TB) or columns (LR). Orthogonal routing, skip-lane edges use free channels. |
| `sequence` | `{participants:[id | {id,label,color}], autonumber?, steps:[{from,to,text,kind?:'sync'|'async'|'return'}, {note,over:id|[id,id]}, {alt|loop|opt|group:'label', steps:[...], else?:{label,steps}|[...]}]}`; `from===to` is a self-call. Tall diagrams are cut between steps across pages with the participant header repeated. |
| `state` | `{states:[id | {id,label?,initial?,terminal?,kind?:'ok'|'error'|'warn'|'neutral',color?,pos?:[col,row]}], transitions:[{from,to,label?}], layout?:'layers'|'circle', direction?:'TB'|'LR'}`. Auto layered layout, back/skip edges curve around nodes, terminal states (no outgoing) get a double border, initial gets a dot. |
| `er` | `{entities:[{name,color?,pos?:[col,row],columns:[{name,type?,pk?,fk?}]}], relations:[{from,to,fromCard,toCard,label?}], columns?}` cardinalities: `'1'`, `'0..1'`, `'N'`, `'0..N'`, `'1..N'` (crow-foot markers). |
| `mindmap` | `{center:'text', branches:[{label,color?,children:[{label,children?}]}]}` (strings allowed). |
| `bar` / `hbar` | `{data:[{label,value,color?}], unit?, max?, height?}` or `{categories:[...], series:[{name,values,color?}]}`; `orientation:'horizontal'` or `type:'hbar'` for horizontal. |

Programmatic use: `buildScene(doc, spec, width) -> {w,h,draw(doc)}` and `renderDiagram(R, spec)` (R = `Renderer`).

## Interview format (`lib/interview.mjs`)
```
### Q01. Question text
**Tags:** Topic, Medium, Design
**Analogy:** kid-friendly analogy
**Model answer:**
any markdown (lists, code, tables)
**Follow-ups:**
- bullet
**Pitfalls:** text
```
`parseInterview(text) -> items[]` (`{kind:'q', num, question, tags, topic, difficulty, type, analogy, answer, followUps, pitfalls}` |
`{kind:'h', level, text}` for `#`/`##` lines | `{kind:'md', text}` for preamble). Rendering: navy question band with `Q01`
badge and tag chips (difficulty is colored easy/medium/hard), green analogy callout, model answer, follow-ups, amber
pitfalls. Each question gets a PDF bookmark (nested under the current heading) and a `q-<n>` destination; questions are not
added to the TOC. `#`/`##` headings in interview files become normal headings (and TOC entries).

## Verification (`verify-pdf.mjs`)
```
node verify-pdf.mjs <pdf> [--min-pages N] [--expect-text "..."]... [--allow-no-outline] [--blank-threshold N] [--dump-page N]
```
Checks: page count; both footer lines on every page; fonts are embedded subsets (no standard-14 fonts); outline present and
the top two levels point to pages that contain their title; every internal GoTo link resolves; TOC page numbers equal the
outline target pages; blank pages (body text < 30 chars); text items outside the page bounds (6 pt margin); expected
strings. Exit code 0 pass / 1 fail / 2 usage or load error. Programmatic: `import { verifyPdf, formatReport } from './lib/verify.mjs'`.

## Fonts
Segoe UI (body), Consolas (code), Arial (header/footer; Montserrat is used if installed) from `C:\Windows\Fonts`, falling
back to DejaVu on Linux/macOS (`PDF_FONT_DIR` overrides the search dir). Glyphs missing from a font are replaced
(`=>` for the double arrows, `->`, curly quotes to straight quotes, ...) or dropped (emoji); fonts are always embedded
unless nothing is found, in which case standard fonts are used and the verifier fails the build.

## Limitations
- No syntax highlighting; no images other than png/jpg; no footnotes, HTML, or nested tables.
- Table rows are never split across pages unless a single row is taller than a page (then split by lines).
- Diagram edge routing is heuristic (no global crossing minimisation); very dense graphs may need `pos`/`col` hints.
- Long tables use measured natural widths; extremely long single words in tables are broken at the cell width.
- Inline `_underscore_` emphasis is only recognised at word boundaries; `*star*` is the safe form.
