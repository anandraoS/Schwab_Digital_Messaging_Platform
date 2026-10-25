#!/usr/bin/env node
// Tiny zero-dependency static server for the Angular production build with an API reverse proxy
// (same job as the dev proxy, but serves what `ng build --configuration production` produced).
//   node scripts/serve-dist.mjs [ui-port=14400] [api-port=18080]
import { createServer, request as httpRequest } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = fileURLToPath(new URL('.', import.meta.url));
const root = resolve(here, '..', 'frontend', 'messaging-console', 'dist', 'messaging-console', 'browser');
const uiPort = Number(process.argv[2] ?? 14400);
const apiPort = Number(process.argv[3] ?? process.env.API_PORT ?? 18080);
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.ico': 'image/x-icon', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.txt': 'text/plain' };

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (/^\/(api|dev|actuator)\//.test(url.pathname)) {
    const proxied = httpRequest({ host: '127.0.0.1', port: apiPort, path: req.url, method: req.method, headers: { ...req.headers, host: `127.0.0.1:${apiPort}` } }, (p) => {
      res.writeHead(p.statusCode ?? 502, p.headers);
      p.pipe(res);
    });
    proxied.on('error', () => { res.writeHead(502); res.end('backend unreachable'); });
    req.pipe(proxied);
    return;
  }
  let file = normalize(join(root, decodeURIComponent(url.pathname)));
  if (!file.startsWith(root)) { res.writeHead(403); res.end(); return; } // no path traversal
  try {
    if ((await stat(file)).isDirectory()) file = join(file, 'index.html');
  } catch {
    file = join(root, 'index.html'); // SPA fallback
  }
  try {
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
}).listen(uiPort, '127.0.0.1', () => console.log(`serving ${root} on http://127.0.0.1:${uiPort} (api proxy -> ${apiPort})`));
