#!/usr/bin/env node
// Browser UI check of the REACT console against the real backend. Zero dependencies: drives headless Chrome through the
// Chrome DevTools Protocol (node:child_process + the global WebSocket of Node 22+), modelled on ui-check.mjs (Angular).
// It signs in as every dev persona, visits all pages, runs the interactive flows (submit + idempotent replay, preferences
// edit, bulk generate, template create/submit/approve with four-eyes, fault injection, queue polling), records console
// errors / uncaught exceptions / failed API calls per step and saves PNG screenshots.
//
//   node tests/end-to-end/react-ui-check.mjs <ui-url> <screenshot-dir>
//   e.g. node tests/end-to-end/react-ui-check.mjs http://127.0.0.1:15200 docs/validation/react-screenshots
//
// Env: CHROME_BIN (default C:\Program Files\Google\Chrome\Application\chrome.exe), CDP_PORT (default 9433),
//      CDP_PROFILE (default: a temp directory).
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const UI = process.argv[2] ?? 'http://127.0.0.1:15200';
const OUT = process.argv[3] ?? 'screenshots';
const CHROME = process.env.CHROME_BIN ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = Number(process.env.CDP_PORT ?? 9433);
const PROFILE = process.env.CDP_PROFILE ?? mkdtempSync(join(tmpdir(), 'react-ui-check-'));
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
let apiCalls = []; // every API request URL seen since start (for polling assertions)
let shots = 0;

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
      if (/\/(api|dev|actuator)\//.test(e.params.response.url)) apiCalls.push(e.params.response.url);
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
  shots += 1;
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

const ERROR_BANNER = `!!document.querySelector('.banner[role=alert]')`;

async function visit(path, name, { settle = '', note = '', noShot = false } = {}) {
  await send('Page.navigate', { url: UI + path });
  await waitFor(`document.readyState === 'complete'`, 15_000, 'load');
  await waitFor(`!!document.querySelector('#root main, #root h1, #root h2')`, 15_000, 'content');
  if (settle) await waitFor(settle, 15_000, `settle(${path})`).catch(() => undefined);
  await sleep(900);
  const banner = await evalJs(ERROR_BANNER);
  const text = await evalJs(`document.body.innerText.slice(0, 1200)`);
  const file = noShot ? '' : await shot(name);
  const d = drain(path);
  const row = { path, title: await evalJs('document.title'), screenshot: file, errorBanner: banner, ...d, textStart: text.replace(/\s+/g, ' ').slice(0, 160), note };
  report.push(row);
  console.log(`${d.consoleErrors.length === 0 && d.apiFailures.length === 0 && !banner ? 'OK  ' : 'WARN'} ${path}  title="${row.title}" consoleErrors=${d.consoleErrors.length} apiFailures=${d.apiFailures.length} banner=${banner}`);
  for (const e of d.consoleErrors) console.log(`       ${e}`);
  for (const e of d.apiFailures) console.log(`       ${e}`);
  return row;
}

// ---- DOM helpers for React-controlled inputs (a plain `.value =` is ignored by React; use the native setter)
const setValue = (sel, val) => evalJs(`(() => {
  const e = document.querySelector(${JSON.stringify(sel)}); if (!e) throw new Error('missing ' + ${JSON.stringify(sel)});
  const proto = e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : e instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, ${JSON.stringify(val)});
  e.dispatchEvent(new Event(e instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  return true; })()`);
const clickText = (text, scope = 'document') => evalJs(`(() => { const b = [...${scope}.querySelectorAll('button')].find((x) => x.textContent.trim() === ${JSON.stringify(text)} && !x.disabled); if (!b) throw new Error('no enabled button ' + ${JSON.stringify(text)}); b.click(); return true; })()`);
const clickSel = (sel) => evalJs(`(() => { const b = document.querySelector(${JSON.stringify(sel)}); if (!b) throw new Error('missing ' + ${JSON.stringify(sel)}); b.click(); return true; })()`);
const goto = async (path) => { await send('Page.navigate', { url: UI + path }); await waitFor(`document.readyState === 'complete'`, 15_000, 'load'); await sleep(300); };

async function signIn(persona) {
  await evalJs(`sessionStorage.clear()`).catch(() => undefined);
  await goto('/login');
  await waitFor(`!!document.querySelector('[data-persona="${persona}"]')`, 15_000, 'login page');
  await evalJs(`sessionStorage.clear()`);
  await clickSel(`[data-persona="${persona}"]`);
  await waitFor(`location.pathname === '/dashboard'`, 20_000, `redirect to dashboard after login as ${persona}`);
  await sleep(500);
  drain(`login:${persona}`);
}

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
    await shot(`98-flow-failed-${name.replace(/\W+/g, '-')}`).catch(() => undefined);
    report.push({ path: `flow:${name}`, consoleErrors: [`flow failed: ${e.message}`], apiFailures: [], errorBanner: false });
  }
};

try {
  await connect();
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Network.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });

  // ---- login page and platform admin tour of every page
  await visit('/login', '01-login');
  const personaIds = await evalJs(`[...document.querySelectorAll('[data-persona]')].map(b => b.dataset.persona)`);
  console.log(`personas offered: ${personaIds.join(', ')}`);
  if (personaIds.length !== 7) throw new Error(`expected 7 personas, saw ${personaIds.length}`);
  await signIn('platform-admin');

  await visit('/dashboard', '02-dashboard', { settle: `!document.body.innerText.includes('Loading summary')` });
  await visit('/messages', '03-messages', { settle: `document.querySelectorAll('.data-table tbody tr').length > 0` });
  await visit('/messages/new', '04-new-message');
  await goto('/messages');
  await waitFor(`!!document.querySelector('.data-table tbody a')`, 20_000, 'message rows');
  const href = await evalJs(`document.querySelector('.data-table tbody a').getAttribute('href')`);
  await visit(href, '05-message-detail', { settle: `!!document.querySelector('.tl li')` });
  await visit('/templates', '06-templates', { settle: `document.querySelectorAll('.data-table tbody tr').length > 0` });
  await goto('/templates');
  await waitFor(`!!document.querySelector('.data-table tbody a')`, 20_000, 'template rows');
  const thref = await evalJs(`document.querySelector('.data-table tbody a').getAttribute('href')`);
  await visit(thref, '07-template-detail', { settle: `!!document.querySelector('[role=tab]')` });
  await visit('/queues', '08-queues', { settle: `document.body.innerText.includes('Last updated')` });
  await visit('/providers', '09-providers', { settle: `document.querySelectorAll('section.card').length > 0` });
  await visit('/bulk-jobs', '10-bulk-jobs');
  await visit('/dead-letters', '11-dead-letters');
  await visit('/failed', '12-failed');
  await visit('/audit', '13-audit', { settle: `document.querySelectorAll('.data-table tbody tr').length > 0` });
  await visit('/delivery-events', '14-delivery-events');
  await visit('/preferences', '15-preferences');
  await visit('/admin', '16-admin', { settle: `document.querySelectorAll('[aria-label^="Configuration of"]').length > 0` });

  // ---- queue page polls quietly (no global loading bar) every 5 s
  await flow('queues-polling', async () => {
    await goto('/queues');
    await waitFor(`document.body.innerText.includes('Last updated')`, 15_000, 'queues loaded');
    apiCalls = [];
    await sleep(11_500);
    drain('poll');
    const polls = (await evalJs(`performance.getEntriesByType('resource').filter((r) => r.name.includes('/api/v1/ops/queues')).length`));
    if (polls < 3) throw new Error(`expected >= 3 queue requests (initial + 2 polls), saw ${polls}`);
    console.log(`       ${polls} /ops/queues requests in ~11.5 s`);
  });

  // ---- submit a message, then replay with the same idempotency key
  await flow('submit-message', async () => {
    await goto('/messages/new');
    await waitFor(`!!document.querySelector('input[name=recipientId]')`, 15_000, 'submit form');
    await sleep(400);
    await setValue('input[name=recipientId]', 'rcp-031');
    await setValue('input[name=templateCode]', 'TRADE_CONFIRMATION');
    await setValue('input[name=locale]', 'en');
    for (const [i, [k, v]] of [['accountLast4', '4321'], ['quantity', '5'], ['symbol', 'ACME'], ['price', '99.50']].entries()) {
      await clickText('Add variable');
      await sleep(120);
      await setValue(`input[name="var-key-${i}"]`, k);
      await setValue(`input[name="var-value-${i}"]`, v);
    }
    await clickText('Submit message');
    await waitFor(`!!document.querySelector('.result-ok') || ${ERROR_BANNER}`, 15_000, 'submit result');
    const text = await evalJs(`(document.querySelector('.result-ok') ?? document.querySelector('.banner')).innerText`);
    await shot('17-message-submitted');
    if (!/Accepted \(HTTP 202\)/.test(text)) throw new Error(`unexpected result: ${text.slice(0, 200)}`);
    console.log(`       ${text.replace(/\s+/g, ' ').slice(0, 120)}`);
    await clickText('Submit message');
    await waitFor(`/Idempotent replay \\(HTTP 200\\)/.test(document.body.innerText)`, 15_000, 'idempotent replay');
    await shot('18-idempotent-replay');
  });

  // ---- preferences edit (null quiet hours = default window, fallbackOrder as channel array)
  await flow('preferences-edit', async () => {
    await goto('/preferences');
    await waitFor(`!!document.querySelector('input[name=q]')`, 15_000, 'search box');
    await sleep(300);
    await setValue('input[name=q]', 'CUST-040');
    await clickText('Search');
    await waitFor(`document.querySelectorAll('.data-table tbody tr').length > 0`, 15_000, 'recipient rows');
    const rowText = await evalJs(`document.querySelector('.data-table tbody tr').innerText`);
    if (!/example\.test|\*\*\*/.test(rowText)) throw new Error(`masked address column empty: ${rowText}`);
    await clickText('Edit preferences');
    await waitFor(`!!document.querySelector('form[aria-label="Preferences form"]')`, 15_000, 'preferences form');
    await sleep(400);
    await setValue('input[name=fallbackOrder]', 'SMS,EMAIL');
    await shot('19-preferences-edit');
    await clickText('Save preferences');
    await waitFor(`/Saved\\./.test(document.body.innerText) || ${ERROR_BANNER} || !!document.querySelector('.conflict')`, 15_000, 'save result');
    if (!(await evalJs(`/Saved\\./.test(document.body.innerText)`))) throw new Error(`save failed: ${await evalJs(`document.body.innerText.slice(0, 400)`)}`);
    await shot('20-preferences-saved');
    // a second save re-uses the version returned by the first (no false conflict)
    await setValue('input[name=fallbackOrder]', 'EMAIL,SMS');
    await clickText('Save preferences');
    await sleep(1200);
    if (await evalJs(`!!document.querySelector('.conflict') || ${ERROR_BANNER}`)) throw new Error('second save unexpectedly failed / conflicted');
  });

  // ---- bulk job: generate synthetic, watch progress to completion
  await flow('bulk-generate', async () => {
    await goto('/bulk-jobs');
    await waitFor(`!!document.querySelector('form[aria-label="Generate synthetic job"]')`, 15_000, 'bulk page');
    await sleep(400);
    await setValue('form[aria-label="Generate synthetic job"] input[name=rows]', '60');
    await clickText('Generate');
    await waitFor(`location.pathname.startsWith('/bulk-jobs/')`, 20_000, 'navigation to the job detail');
    await waitFor(`/COMPLETED/.test(document.body.innerText)`, 60_000, 'job completion shown');
    await shot('21-bulk-job-detail');
  });

  // ---- platform admin fault injection (probability 0 and NONE: harmless) and provider config round trip
  await flow('provider-fault-injection', async () => {
    await goto('/providers');
    await waitFor(`!!document.querySelector('fieldset.fault')`, 15_000, 'fault injection form');
    await sleep(300);
    const id = await evalJs(`document.querySelector('fieldset.fault select').id`);
    await setValue(`#${id}`, 'TRANSIENT');
    const pid = await evalJs(`document.querySelector('fieldset.fault input[type=number]').id`);
    await setValue(`#${pid}`, '0');
    await clickText('Apply');
    await waitFor(`/set to TRANSIENT/.test(document.body.innerText) || ${ERROR_BANNER}`, 15_000, 'fault result');
    if (!(await evalJs(`/set to TRANSIENT/.test(document.body.innerText)`))) throw new Error('fault injection was refused');
    await shot('22-provider-fault-injected');
    await setValue(`#${id}`, 'NONE');
    await clickText('Apply');
    await waitFor(`/set to NONE/.test(document.body.innerText)`, 15_000, 'fault reset');
  });
  await visit('/dead-letters', '23-dead-letters-after');

  // ---- template lifecycle with four-eyes: author creates + submits, approver approves
  const code = `UI_REACT_${Date.now().toString(36).toUpperCase()}`;
  let templateUrl = '';
  await flow('create-template-as-author', async () => {
    await signIn('template-author');
    await goto('/templates');
    await waitFor(`!!document.querySelector('.data-table')`, 15_000, 'templates');
    await sleep(400);
    await clickText('New template');
    await waitFor(`!!document.querySelector('form[aria-label="Template editor"] input[name=code]')`, 10_000, 'editor');
    const F = 'form[aria-label="Template editor"]';
    await setValue(`${F} input[name=code]`, code);
    await setValue(`${F} input[name=name]`, 'React UI check template');
    await setValue(`${F} input[name=subject]`, 'Hello {{who}}');
    await setValue(`${F} textarea[name=body]`, '<p>Hi {{who}}</p>');
    await setValue(`${F} input[name=variables]`, 'who');
    await shot('24-template-create');
    await clickText('Create template', `document.querySelector('${F}')`);
    await waitFor(`/^\\/templates\\/[^/]+$/.test(location.pathname)`, 15_000, 'navigation to the new template');
    await waitFor(`/DRAFT/.test(document.body.innerText)`, 15_000, 'draft version shown');
    templateUrl = await evalJs('location.pathname');
    await shot('25-template-created');
    await clickText('Submit for approval');
    await waitFor(`/PENDING APPROVAL/.test(document.body.innerText)`, 15_000, 'pending approval');
    await shot('26-template-pending');
  });
  await flow('approve-template-as-approver', async () => {
    if (!templateUrl) throw new Error('no template was created');
    await signIn('template-approver');
    await goto(templateUrl);
    await waitFor(`/PENDING APPROVAL/.test(document.body.innerText)`, 15_000, 'pending version visible');
    await clickText('Approve');
    await waitFor(`!!document.querySelector('[role=alertdialog]')`, 5_000, 'confirmation dialog');
    await shot('27-approve-confirm');
    await clickText('Approve', `document.querySelector('[role=alertdialog]')`);
    await waitFor(`/approve succeeded/.test(document.body.innerText) || ${ERROR_BANNER}`, 15_000, 'approve result');
    await waitFor(`/PUBLISHED/.test(document.body.innerText)`, 15_000, 'published');
    await shot('28-template-published');
  });

  // ---- remaining personas: landing page + their typical page, no errors
  for (const [persona, paths] of [
    ['sender', ['/dashboard', '/messages/new', '/bulk-jobs']],
    ['operator', ['/dashboard', '/queues', '/dead-letters', '/failed', '/providers']],
    ['auditor', ['/dashboard', '/audit', '/delivery-events']],
    ['tenant-admin', ['/dashboard', '/templates', '/audit', '/dead-letters']],
  ]) {
    await flow(`persona-${persona}`, async () => {
      await signIn(persona);
      for (const p of paths) {
        await visit(p, `99-${persona}-${p.replace(/\W+/g, '-')}`, { noShot: true });
      }
      if (persona === 'auditor') await shot('29-auditor-audit');
      if (persona === 'operator') await shot('30-operator-queues');
    });
  }
  await flow('forbidden-guard', async () => {
    await signIn('auditor');
    await goto('/queues');
    await waitFor(`document.body.innerText.includes('Not permitted')`, 10_000, 'forbidden page (UX guard)');
    await shot('31-forbidden');
  });
  await flow('responsive-mobile', async () => {
    await signIn('operator');
    await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await goto('/messages');
    await waitFor(`!!document.querySelector('.data-table, .empty')`, 15_000, 'messages on mobile');
    await sleep(500);
    await shot('32-mobile-messages');
    await clickSel('.menu-btn');
    await sleep(300);
    await shot('33-mobile-menu');
    await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  });

  writeFileSync(join(OUT, 'react-ui-check-report.json'), JSON.stringify({ ui: UI, at: new Date().toISOString(), screenshots: shots, pages: report }, null, 2));
  const bad = report.filter((r) => r.consoleErrors.length || r.apiFailures.length || r.errorBanner);
  console.log(`\nReact UI check finished: ${report.length} steps, ${bad.length} with problems, ${shots} screenshots saved to ${OUT}`);
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
