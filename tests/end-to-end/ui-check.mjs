#!/usr/bin/env node
// Browser UI check against the real backend, zero dependencies: drives headless Chrome through the Chrome DevTools
// Protocol (node:child_process + the global WebSocket of Node 22+). It signs in as a dev persona, visits the main
// pages, records console errors / uncaught exceptions / failed API calls per page and saves PNG screenshots.
//
//   node tests/end-to-end/ui-check.mjs <ui-url> <screenshot-dir> [persona-id ...]
//   e.g. node tests/end-to-end/ui-check.mjs http://127.0.0.1:14300 docs/validation/screenshots platform-admin
//
// Env: CHROME_BIN (default C:\Program Files\Google\Chrome\Application\chrome.exe), CDP_PORT (default 9333).
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const UI = process.argv[2] ?? 'http://127.0.0.1:14300';
const OUT = process.argv[3] ?? 'screenshots';
const PERSONA = process.argv[4] ?? 'platform-admin';
const CHROME = process.env.CHROME_BIN ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = Number(process.env.CDP_PORT ?? 9333);
const PROFILE = process.env.CDP_PROFILE ?? join(process.cwd(), '.work', 'chrome-profile');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

mkdirSync(OUT, { recursive: true });
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`, '--no-sandbox', '--disable-gpu',
  '--window-size=1440,1000', '--hide-scrollbars', 'about:blank',
], { stdio: 'ignore' });

let ws;
let nextId = 1;
const pending = new Map();
const events = [];
function send(method, params = {}) {
  const id = nextId++;
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

async function connect() {
  for (let i = 0; i < 60; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const page = list.find((t) => t.type === 'page');
      if (page) {
        ws = new WebSocket(page.webSocketDebuggerUrl);
        await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
        ws.onmessage = (m) => {
          const msg = JSON.parse(m.data);
          if (msg.id && pending.has(msg.id)) {
            const p = pending.get(msg.id);
            pending.delete(msg.id);
            msg.error ? p.reject(new Error(JSON.stringify(msg.error))) : p.resolve(msg.result);
          } else if (msg.method) {
            events.push(msg);
          }
        };
        return;
      }
    } catch {
      /* chrome not up yet */
    }
    await sleep(250);
  }
  throw new Error('could not connect to Chrome DevTools');
}

const evalJs = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(`eval failed: ${r.exceptionDetails.exception?.description ?? JSON.stringify(r.exceptionDetails).slice(0, 300)}`);
  return r.result.value;
};

const report = [];
let netStatus = new Map(); // requestId -> {url, status}

/** Summarises the console/network events seen since the last call. */
function drain(label) {
  const errs = [];
  const apiFailures = [];
  for (const e of events.splice(0)) {
    if (e.method === 'Runtime.consoleAPICalled' && ['error', 'assert'].includes(e.params.type)) {
      errs.push(`console.${e.params.type}: ${e.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 300)}`);
    } else if (e.method === 'Runtime.exceptionThrown') {
      errs.push(`exception: ${(e.params.exceptionDetails.exception?.description ?? e.params.exceptionDetails.text).slice(0, 300)}`);
    } else if (e.method === 'Log.entryAdded' && e.params.entry.level === 'error') {
      // resource 4xx/5xx are reported separately below; keep the rest
      if (!/Failed to load resource/.test(e.params.entry.text)) errs.push(`log.error: ${e.params.entry.text.slice(0, 300)}`);
    } else if (e.method === 'Network.responseReceived') {
      netStatus.set(e.params.requestId, { url: e.params.response.url, status: e.params.response.status });
    }
  }
  for (const r of netStatus.values()) {
    if (r.status >= 400 && /\/(api|dev|actuator)\//.test(r.url)) apiFailures.push(`${r.status} ${r.url.replace(/^https?:\/\/[^/]+/, '')}`);
  }
  netStatus = new Map();
  return { label, consoleErrors: errs, apiFailures };
}

async function shot(name) {
  const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  const file = join(OUT, `${name}.png`);
  writeFileSync(file, Buffer.from(r.data, 'base64'));
  return file;
}

async function waitFor(js, timeoutMs = 15_000, what = js) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    try {
      if (await evalJs(js)) return true;
    } catch {
      /* page navigating */
    }
    await sleep(200);
  }
  throw new Error(`timeout waiting for ${what}`);
}

async function visit(path, name, { settle = '', note = '' } = {}) {
  await send('Page.navigate', { url: UI + path });
  await waitFor(`document.readyState === 'complete'`, 15_000, 'load');
  await sleep(300);
  await waitFor(`!!document.querySelector('app-root') && (document.querySelector('main, h1, h2') !== null)`, 15_000, 'content');
  if (settle) await waitFor(settle, 15_000, `settle(${path})`).catch(() => undefined);
  await sleep(1200);
  const text = await evalJs(`document.body.innerText.slice(0, 1200)`);
  const banner = await evalJs(`!!document.querySelector('app-error-banner [role=alert], .error-banner, .banner-error')`);
  const file = await shot(name);
  const d = drain(path);
  const row = { path, title: await evalJs('document.title'), screenshot: file, errorBanner: banner, ...d, textStart: text.replace(/\s+/g, ' ').slice(0, 160), note };
  report.push(row);
  console.log(`${d.consoleErrors.length === 0 && d.apiFailures.length === 0 && !banner ? 'OK  ' : 'WARN'} ${path}  title="${row.title}" consoleErrors=${d.consoleErrors.length} apiFailures=${d.apiFailures.length} banner=${banner}`);
  for (const e of d.consoleErrors) console.log(`       ${e}`);
  for (const e of d.apiFailures) console.log(`       ${e}`);
  return row;
}

try {
  await connect();
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Network.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });

  await visit('/login', '01-login');
  const personaIds = await evalJs(`[...document.querySelectorAll('[data-persona]')].map(b => b.dataset.persona)`);
  console.log(`personas offered: ${personaIds.join(', ')}`);
  await evalJs(`document.querySelector('[data-persona="${PERSONA}"]').click()`);
  await waitFor(`location.pathname === '/dashboard'`, 20_000, 'redirect to dashboard after login');
  await sleep(500);
  drain('login');

  if (process.env.UI_EVAL) {
    await visit('/' + (process.env.UI_EVAL_PATH ?? 'messages/new'), '99-debug'); // path without leading slash (MSYS path mangling)
    console.log(JSON.stringify(await evalJs(process.env.UI_EVAL), null, 1));
    throw new Error('debug eval done');
  }
  await visit('/dashboard', '02-dashboard', { settle: `!document.body.innerText.includes('Loading')` });
  await visit('/messages', '03-messages', { settle: `document.querySelectorAll('app-data-table tbody tr').length > 0` });
  await visit('/messages/new', '04-new-message');
  // open the first message of the list
  await send('Page.navigate', { url: UI + '/messages' });
  await waitFor(`!!document.querySelector('app-data-table tbody a')`, 20_000, 'message rows');
  const href = await evalJs(`document.querySelector('app-data-table tbody a').getAttribute('href')`);
  await visit(href, '05-message-detail', { settle: `!!document.querySelector('app-timeline li')` });
  await visit('/templates', '06-templates', { settle: `document.querySelectorAll('app-data-table tbody tr').length > 0` });
  await send('Page.navigate', { url: UI + '/templates' });
  await waitFor(`!!document.querySelector('app-data-table tbody a')`, 20_000, 'template rows');
  const thref = await evalJs(`document.querySelector('app-data-table tbody a').getAttribute('href')`);
  await visit(thref, '07-template-detail');
  await visit('/queues', '08-queues');
  await visit('/providers', '09-providers');
  await visit('/bulk-jobs', '10-bulk-jobs');
  await visit('/dead-letters', '11-dead-letters');
  await visit('/failed', '12-failed');
  await visit('/audit', '13-audit');
  await visit('/delivery-events', '14-delivery-events');
  await visit('/preferences', '15-preferences');
  await visit('/admin', '16-admin');

  // ---- interactions against the real backend (forms, validation, writes)
  const setValue = (sel, val) => evalJs(`(() => { const e = document.querySelector(${JSON.stringify(sel)}); if (!e) throw new Error('missing ' + ${JSON.stringify(sel)}); e.focus(); e.value = ${JSON.stringify(val)}; e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('blur')); return true; })()`);
  const clickText = (text, scope = 'document') => evalJs(`(() => { const b = [...${scope}.querySelectorAll('button')].find((x) => x.textContent.trim().includes(${JSON.stringify(text)})); if (!b) throw new Error('no button ' + ${JSON.stringify(text)}); b.click(); return true; })()`);
  const flow = async (name, fn) => {
    try {
      await fn();
      const d = drain(name);
      const ok = d.consoleErrors.length === 0 && d.apiFailures.length === 0;
      console.log(`${ok ? 'OK  ' : 'WARN'} flow: ${name} consoleErrors=${d.consoleErrors.length} apiFailures=${d.apiFailures.length}`);
      for (const e of [...d.consoleErrors, ...d.apiFailures]) console.log(`       ${e}`);
      report.push({ path: `flow:${name}`, ...d, errorBanner: false });
    } catch (e) {
      console.log(`FAIL flow: ${name}: ${e.message}`);
      await shot(`98-flow-failed-${name.replace(/\W+/g, '-')}`);
      report.push({ path: `flow:${name}`, consoleErrors: [`flow failed: ${e.message}`], apiFailures: [], errorBanner: false });
    }
  };

  await flow('submit-message', async () => {
    await send('Page.navigate', { url: UI + '/messages/new' });
    await waitFor(`!!document.querySelector('input[formcontrolname=recipientId]')`, 15_000, 'submit form');
    await sleep(500);
    await setValue('input[formcontrolname=recipientId]', 'rcp-031');
    await setValue('input[formcontrolname=templateCode]', 'TRADE_CONFIRMATION');
    await setValue('input[formcontrolname=locale]', 'en');
    for (const [k, v] of [['accountLast4', '4321'], ['quantity', '5'], ['symbol', 'ACME'], ['price', '99.50']]) {
      await clickText('Add variable');
      await sleep(150);
      const n = await evalJs(`document.querySelectorAll('[formarrayname=variables] .var-row').length`);
      await setValue(`[formarrayname=variables] .var-row:nth-of-type(${n}) input[formcontrolname=key]`, k);
      await setValue(`[formarrayname=variables] .var-row:nth-of-type(${n}) input[formcontrolname=value]`, v);
    }
    await clickText('Submit message');
    await waitFor(`!!document.querySelector('.card.ok') || !!document.querySelector('app-error-banner [role=alert]')`, 15_000, 'submit result');
    const text = await evalJs(`(document.querySelector('.card.ok') ?? document.querySelector('app-error-banner')).innerText`);
    await shot('17-message-submitted');
    if (!/Accepted \(HTTP 202\)/.test(text)) throw new Error(`unexpected result: ${text.slice(0, 200)}`);
    console.log(`       ${text.replace(/\s+/g, ' ').slice(0, 120)}`);
    // submit again with the same idempotency key: replay
    await clickText('Submit message');
    await waitFor(`/Idempotent replay/.test(document.body.innerText)`, 15_000, 'idempotent replay');
  });

  await flow('preferences-edit', async () => {
    await send('Page.navigate', { url: UI + '/preferences' });
    await waitFor(`!!document.querySelector('input[formcontrolname=q]')`, 15_000, 'search box');
    await sleep(400);
    await setValue('input[formcontrolname=q]', 'CUST-040');
    await clickText('Search');
    await waitFor(`document.querySelectorAll('app-data-table tbody tr').length > 0`, 15_000, 'recipient rows');
    const rowText = await evalJs(`document.querySelector('app-data-table tbody tr').innerText`);
    if (!/example\.test|\*\*\*/.test(rowText)) throw new Error(`masked address column empty: ${rowText}`);
    await clickText('Edit preferences');
    await waitFor(`!!document.querySelector('form[aria-label="Preferences form"]')`, 15_000, 'preferences form');
    await sleep(500);
    await setValue('input[formcontrolname=fallbackOrder]', 'SMS,EMAIL');
    await shot('18-preferences-edit');
    await clickText('Save preferences');
    await waitFor(`/Saved\./.test(document.body.innerText) || !!document.querySelector('app-error-banner [role=alert]')`, 15_000, 'save result');
    if (!(await evalJs(`/Saved\./.test(document.body.innerText)`))) throw new Error(`save failed: ${await evalJs(`document.querySelector('app-error-banner').innerText`)}`);
    await shot('19-preferences-saved');
  });

  await flow('bulk-generate', async () => {
    await send('Page.navigate', { url: UI + '/bulk-jobs' });
    await waitFor(`!!document.querySelector('form[aria-label="Generate synthetic job"]')`, 15_000, 'bulk page');
    await sleep(500);
    await evalJs(`(() => { const r = document.querySelector('form[aria-label="Generate synthetic job"] input[formcontrolname=rows]'); r.value = '60'; r.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
    await clickText('Generate');
    await waitFor(`location.pathname.startsWith('/bulk-jobs/')`, 20_000, 'navigation to the job detail');
    await waitFor(`/COMPLETED/.test(document.body.innerText)`, 60_000, 'job completion shown');
    await shot('22-bulk-job-detail');
  });

  await visit('/dead-letters', '23-dead-letters-after');
  await flow('create-template', async () => {
    // creating templates needs the TEMPLATE_AUTHOR role: sign in as that persona through the login page
    await evalJs(`sessionStorage.clear()`);
    await send('Page.navigate', { url: UI + '/login' });
    await waitFor(`!!document.querySelector('[data-persona="template-author"]')`, 15_000, 'login page');
    await evalJs(`document.querySelector('[data-persona="template-author"]').click()`);
    await waitFor(`location.pathname === '/dashboard'`, 20_000, 'redirect after author login');
    await send('Page.navigate', { url: UI + '/templates' });
    await waitFor(`!!document.querySelector('app-data-table')`, 15_000, 'templates');
    await sleep(500);
    await clickText('New template');
    await waitFor(`!!document.querySelector('app-template-editor input[formcontrolname=code]')`, 10_000, 'editor');
    const code = `UI_CHECK_${Date.now().toString(36).toUpperCase()}`;
    await setValue('app-template-editor input[formcontrolname=code]', code);
    await setValue('app-template-editor input[formcontrolname=name]', 'UI check template');
    await setValue('app-template-editor input[formcontrolname=subject]', 'Hello {{who}}');
    await setValue('app-template-editor textarea[formcontrolname=body]', '<p>Hi {{who}}</p>');
    await setValue('app-template-editor input[formcontrolname=variables]', 'who');
    await shot('20-template-create');
    await clickText('Create template', 'document.querySelector(\'app-template-editor\')');
    await waitFor(`location.pathname.startsWith('/templates/')`, 15_000, 'navigation to the new template');
    await waitFor(`/DRAFT/.test(document.body.innerText)`, 15_000, 'draft version shown');
    await shot('21-template-created');
  });

  writeFileSync(join(OUT, 'ui-check-report.json'), JSON.stringify({ ui: UI, persona: PERSONA, at: new Date().toISOString(), pages: report }, null, 2));
  const bad = report.filter((r) => r.consoleErrors.length || r.apiFailures.length || r.errorBanner);
  console.log(`\nUI check finished: ${report.length} pages, ${bad.length} with problems`);
  process.exitCode = bad.length ? 1 : 0;
} catch (e) {
  console.log(`UI check aborted: ${e.stack ?? e}`);
  try {
    await shot('99-abort');
    console.log(`url at abort: ${await evalJs('location.href')}; text: ${(await evalJs('document.body.innerText.slice(0,300)')).replace(/\s+/g, ' ')}`);
    console.log(JSON.stringify(drain('abort')));
  } catch { /* ignore */ }
  process.exitCode = 2;
} finally {
  try { ws?.close(); } catch { /* ignore */ }
  spawnSync('taskkill', ['/PID', String(chrome.pid), '/T', '/F'], { stdio: 'ignore' });
}
