#!/usr/bin/env node
// Dependency-free CF manifest validator (offline friendly). Uses the in-repo YAML-subset parser.
// Usage: node cloud-foundry/validate-manifest.mjs [manifest.yml ...]   (defaults to both manifests in this folder)
// Checks: parses; applications[] present; name/memory/instances/buildpacks; memory format (e.g. 512M, 1G, ((var)));
//         health-check-type in {http,port,process}; http type requires health-check-http-endpoint starting with '/';
//         routes shape; java manifest pins JBP_CONFIG_OPEN_JDK_JRE for Java 21; no inline secrets in env; services are strings.
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseYaml } from '../scripts/lib/yaml-lite.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const files = process.argv.length > 2 ? process.argv.slice(2) : [resolve(here, 'manifest.yml'), resolve(here, 'manifest-frontend.yml')];

const MEM = /^(\d+(\.\d+)?)(M|MB|G|GB)$/i;
const VAR = /^\(\(.+\)\)$/;
const SECRET_KEY = /(password|passwd|secret|token|api[_-]?key|private[_-]?key|credential)/i;
const ALLOWED_HEALTH = new Set(['http', 'port', 'process']);

function validate(file) {
  const errs = [];
  const warn = [];
  let doc;
  try { doc = parseYaml(readFileSync(file, 'utf8')); } catch (e) { return { errs: [`YAML parse error: ${e.message}`], warn }; }
  if (!doc || !Array.isArray(doc.applications) || doc.applications.length === 0) return { errs: ['missing non-empty "applications" list'], warn };
  doc.applications.forEach((app, idx) => {
    const p = `applications[${idx}]`;
    if (!app.name || typeof app.name !== 'string') errs.push(`${p}.name required`);
    if (app.memory === undefined) errs.push(`${p}.memory required`);
    else if (!(VAR.test(String(app.memory)) || MEM.test(String(app.memory)))) errs.push(`${p}.memory "${app.memory}" must look like 512M / 1G / ((var))`);
    if (app.disk_quota !== undefined && !(VAR.test(String(app.disk_quota)) || MEM.test(String(app.disk_quota)))) errs.push(`${p}.disk_quota "${app.disk_quota}" invalid`);
    if (app.instances !== undefined && !(VAR.test(String(app.instances)) || (Number.isInteger(app.instances) && app.instances >= 0))) errs.push(`${p}.instances must be a non-negative integer or ((var))`);
    if (!Array.isArray(app.buildpacks) || app.buildpacks.length === 0) errs.push(`${p}.buildpacks must be a non-empty list`);
    const hct = app['health-check-type'];
    if (!hct) errs.push(`${p}.health-check-type required`);
    else if (!ALLOWED_HEALTH.has(hct)) errs.push(`${p}.health-check-type "${hct}" not in http|port|process`);
    if (hct === 'http') {
      const ep = app['health-check-http-endpoint'];
      if (!ep || !String(ep).startsWith('/')) errs.push(`${p}.health-check-http-endpoint must start with "/" when type is http`);
    } else if (app['health-check-http-endpoint']) errs.push(`${p}.health-check-http-endpoint only valid with health-check-type http`);
    if (app.timeout !== undefined && !(Number.isInteger(app.timeout) && app.timeout > 0 && app.timeout <= 600)) errs.push(`${p}.timeout must be 1..600 seconds`);
    if (app.routes !== undefined) {
      if (!Array.isArray(app.routes) || app.routes.some((r) => !r || typeof r.route !== 'string')) errs.push(`${p}.routes must be a list of { route: <string> }`);
    }
    if (app.services !== undefined && (!Array.isArray(app.services) || app.services.some((s) => typeof s !== 'string'))) errs.push(`${p}.services must be a list of service instance names`);
    const isJava = Array.isArray(app.buildpacks) && app.buildpacks.includes('java_buildpack');
    const env = app.env || {};
    if (isJava) {
      if (!env.JBP_CONFIG_OPEN_JDK_JRE || !/21/.test(String(env.JBP_CONFIG_OPEN_JDK_JRE))) errs.push(`${p}.env.JBP_CONFIG_OPEN_JDK_JRE must select Java 21`);
      if (!app.path) errs.push(`${p}.path (jar) required for java_buildpack`);
      for (const svc of ['messaging-postgres', 'messaging-rabbit', 'messaging-redis', 'messaging-mongo']) {
        if (!(app.services || []).includes(svc)) errs.push(`${p}.services must bind ${svc}`);
      }
    }
    for (const [k, v] of Object.entries(env)) {
      if (SECRET_KEY.test(k) && v !== null && !VAR.test(String(v)) && !/^(true|false)$/i.test(String(v))) errs.push(`${p}.env.${k} looks like an inline secret (use cf set-env, user-provided service or ((var)))`);
      if (/(BEGIN [A-Z ]*PRIVATE KEY|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{20,}\.)/.test(String(v))) errs.push(`${p}.env.${k} contains secret-looking material`);
    }
    if (app.instances !== undefined && Number.isInteger(app.instances) && app.instances < 2) warn.push(`${p}.instances < 2 (fine for dev; use >=2 for HA)`);
  });
  return { errs, warn };
}

let failed = 0;
for (const f of files) {
  if (!existsSync(f)) { console.log(`FAIL ${f}: file not found`); failed++; continue; }
  const { errs, warn } = validate(f);
  if (errs.length === 0) console.log(`OK   ${f}`);
  else { failed++; console.log(`FAIL ${f}`); errs.forEach((e) => console.log(`       - ${e}`)); }
  warn.forEach((w) => console.log(`     warn: ${w}`));
}
// vars files: parse + required keys
for (const f of files.length === 2 && files[0].endsWith('manifest.yml') ? ['dev', 'test'].map((n) => resolve(here, 'vars', `${n}.yml`)) : []) {
  try {
    const v = parseYaml(readFileSync(f, 'utf8'));
    const need = ['env', 'instances', 'memory', 'apps_domain'];
    const miss = need.filter((k) => !(k in v));
    if (miss.length) { failed++; console.log(`FAIL ${f}: missing ${miss.join(', ')}`); }
    else if (!MEM.test(String(v.memory))) { failed++; console.log(`FAIL ${f}: memory format`); }
    else console.log(`OK   ${f}`);
  } catch (e) { failed++; console.log(`FAIL ${f}: ${e.message}`); }
}
process.exit(failed ? 1 : 0);
