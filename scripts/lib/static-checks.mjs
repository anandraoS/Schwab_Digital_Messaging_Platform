#!/usr/bin/env node
// Static repo checks that need no external tools. Usage: node scripts/lib/static-checks.mjs <repoRoot>
// Each check prints PASS/FAIL. These are heuristics, not substitutes for promtool/kubeconform/docker compose config.
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, resolve, sep } from 'node:path';
import vm from 'node:vm';
import { parseYaml } from './yaml-lite.mjs';

const root = resolve(process.argv[2] || '.');
let failures = 0;
const rd = (p) => readFileSync(join(root, p), 'utf8');
function result(name, errs) {
  if (errs.length === 0) console.log(`PASS ${name}`);
  else { failures++; console.log(`FAIL ${name}`); errs.forEach((e) => console.log(`       - ${e}`)); }
}
function* walk(dir, skip = new Set(['node_modules', 'target', '.git', '.angular', 'dist', '.work', 'tools'])) {
  if (!existsSync(dir)) return;
  for (const e of readdirSync(dir)) {
    if (skip.has(e)) continue;
    const p = join(dir, e);
    if (statSync(p).isDirectory()) yield* walk(p, skip); else yield p;
  }
}

// 1. alerts
{
  const errs = [];
  const doc = parseYaml(rd('observability/prometheus/alerts.yml'));
  const rules = doc.groups.flatMap((g) => g.rules);
  const allowed = new Set(['provider-outage', 'queue-backlog', 'dlq-growth', 'retry-storm', 'callback-signature-failures', 'database-outage', 'redis-outage', 'bulk-job-restart', 'consumer-crash-ambiguous-outcomes', 'provider-rate-limit', 'high-api-latency']);
  if (rules.length < 12) errs.push(`only ${rules.length} alerts (need >= 12)`);
  const used = new Set();
  for (const r of rules) {
    if (!r.alert || !r.expr || !r.annotations) { errs.push(`rule missing alert/expr/annotations: ${JSON.stringify(r).slice(0, 60)}`); continue; }
    const m = /^docs\/operations\/runbooks\/([a-z-]+)\.md$/.exec(r.annotations.runbook_url || '');
    if (!m || !allowed.has(m[1])) errs.push(`${r.alert}: runbook_url invalid (${r.annotations.runbook_url})`); else used.add(m[1]);
    if (!r.labels || !r.labels.severity) errs.push(`${r.alert}: severity label missing`);
    const bal = (s, a, b) => (s.split(a).length - 1) === (s.split(b).length - 1);
    if (!bal(r.expr, '(', ')') || !bal(r.expr, '[', ']') || !bal(r.expr, '{', '}')) errs.push(`${r.alert}: unbalanced brackets in expr`);
  }
  console.log(`     alerts=${rules.length} distinct_runbooks_used=${used.size}/${allowed.size}`);
  result('alerts.yml structure (>=12 alerts, runbook names, severity, bracket balance)', errs);
}

// 2. metric names used in rules/dashboards exist in contract list
{
  const errs = [];
  const contract = new Set(['messaging_messages_accepted_total', 'messaging_transitions_total', 'messaging_dispatch_delay_seconds_bucket', 'messaging_dispatch_delay_seconds_count', 'messaging_provider_calls_total', 'messaging_provider_latency_seconds_bucket', 'messaging_provider_circuit_state', 'messaging_queue_depth', 'messaging_queue_inflight', 'messaging_queue_dlq_depth', 'messaging_retry_scheduled_total', 'messaging_deadletter_total', 'messaging_callbacks_total', 'messaging_bulk_progress', 'messaging_idempotency_hits_total', 'messaging_ratelimit_rejected_total', 'messaging_redis_fallback_total', 'messaging_diagnostics_write_failures_total']);
  const files = [...walk(join(root, 'observability'))].filter((f) => /\.(yml|json)$/.test(f) && !f.includes('provisioning'));
  for (const f of files) {
    const t = readFileSync(f, 'utf8');
    for (const m of t.matchAll(/\bmessaging_[a-z0-9_]+/g)) if (!contract.has(m[0])) errs.push(`${f.replace(root, '')}: ${m[0]} is not a CONTRACT section 8 metric`);
  }
  result('observability metric names limited to CONTRACT section 8 (Prometheus form)', [...new Set(errs)]);
}

// 3. compose heuristics
{
  const errs = [];
  const doc = parseYaml(rd('infrastructure/compose/docker-compose.yml'));
  for (const [name, s] of Object.entries(doc.services)) {
    const img = s.image || '';
    if (!s.build && !/:[\w][\w.\-]*$/.test(img)) errs.push(`${name}: image "${img}" has no pinned tag`);
    if (/:latest$/.test(img)) errs.push(`${name}: uses :latest`);
    if (!s.deploy || !s.deploy.resources) { if (name !== 'k6') errs.push(`${name}: no resource limits`); }
    if (!s.healthcheck && !['frontend', 'otel-collector', 'k6'].includes(name) && !s.profiles) errs.push(`${name}: no healthcheck`);
  }
  for (const need of ['postgres', 'rabbitmq', 'redis', 'mongo', 'backend', 'frontend', 'prometheus', 'grafana', 'otel-collector', 'k6']) if (!doc.services[need]) errs.push(`missing service ${need}`);
  // secrets must be variables, never literal
  const raw = rd('infrastructure/compose/docker-compose.yml');
  for (const m of raw.matchAll(/^\s*\w*(PASSWORD|SECRET)\w*:\s*(.+)$/gm)) if (!/\$\{/.test(m[2])) errs.push(`literal credential-like value: ${m[0].trim()}`);
  result('docker-compose.yml heuristics (services, pinned tags, limits, healthchecks, no literal secrets)', errs);
}

// 4. k8s manifests heuristics
{
  const errs = [];
  const dep = parseYaml(rd('infrastructure/kubernetes/base/deployment.yaml'));
  const c = dep.spec.template.spec.containers[0];
  for (const p of ['startupProbe', 'livenessProbe', 'readinessProbe']) if (!c[p]) errs.push(`deployment missing ${p}`);
  if (!/health\/liveness/.test(c.livenessProbe.httpGet.path)) errs.push('liveness path wrong');
  if (!/health\/readiness/.test(c.readinessProbe.httpGet.path)) errs.push('readiness path wrong');
  if (c.securityContext.readOnlyRootFilesystem !== true) errs.push('readOnlyRootFilesystem not true');
  if (dep.spec.template.spec.securityContext.runAsNonRoot !== true) errs.push('runAsNonRoot not true');
  if (!c.lifecycle || !c.lifecycle.preStop) errs.push('no preStop hook');
  if (!c.resources || !c.resources.limits || !c.resources.requests) errs.push('resources missing');
  if (!dep.spec.template.spec.topologySpreadConstraints) errs.push('topologySpreadConstraints missing');
  result('kubernetes base deployment heuristics (probes, non-root, read-only FS, preStop, resources, spread)', errs);
}

// 5. helm template balance (cannot render without helm)
{
  const errs = [];
  const dir = join(root, 'infrastructure/helm/messaging-platform/templates');
  for (const f of walk(dir)) {
    const t = readFileSync(f, 'utf8');
    const opens = (t.match(/\{\{-?\s*(if|range|with|define)\b/g) || []).length;
    const ends = (t.match(/\{\{-?\s*end\b/g) || []).length;
    if (opens !== ends) errs.push(`${f.split(sep).pop()}: ${opens} block openers vs ${ends} {{ end }}`);
    if ((t.match(/\{\{/g) || []).length !== (t.match(/\}\}/g) || []).length) errs.push(`${f.split(sep).pop()}: unbalanced {{ }}`);
  }
  result('helm templates: balanced {{ }} and block/end counts (NOT a render)', errs);
}

// 6. JS syntax of k6 + node scripts (parse only, as ES modules)
{
  const errs = [];
  const files = [...walk(join(root, 'performance')), ...walk(join(root, 'scripts')), ...walk(join(root, 'cloud-foundry')), ...walk(join(root, 'observability'))].filter((f) => /\.(m?js)$/.test(f));
  for (const f of files) {
    try {
      const src = readFileSync(f, 'utf8').replace(/^#!.*/, '');
      if (!vm.SourceTextModule) throw new Error('run node with --experimental-vm-modules');
      new vm.SourceTextModule(src, { identifier: f });
    } catch (e) { errs.push(`${f.replace(root, '')}: ${e.message}`); }
  }
  console.log(`     js files parsed: ${files.length}`);
  result('JavaScript syntax (k6 scripts + node tools), parse only', errs);
}

// 7. secret scan over files this agent owns
{
  const errs = [];
  const pats = [/AKIA[0-9A-Z]{16}/, /-----BEGIN [A-Z ]*PRIVATE KEY-----/, /ghp_[A-Za-z0-9]{30,}/, /xox[abp]-[A-Za-z0-9-]{10,}/, /eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}\./];
  const dirs = ['infrastructure', 'cloud-foundry', 'observability', 'performance', '.github', 'bamboo-specs', 'scripts'];
  const files = [...dirs.flatMap((d) => [...walk(join(root, d))]), join(root, 'Jenkinsfile'), join(root, '.env.example')].filter((f) => existsSync(f));
  for (const f of files) {
    const t = readFileSync(f, 'utf8');
    if (/static-checks\.mjs$/.test(f)) continue;
    for (const p of pats) if (p.test(t)) errs.push(`${f.replace(root, '')}: matches ${p}`);
  }
  console.log(`     files scanned: ${files.length}`);
  result('secret-pattern scan (AWS key, private key, GitHub/Slack tokens, JWT) over infra/CI/scripts', errs);
}

// 8. CI files reference only credential IDs / secrets context
{
  const errs = [];
  for (const f of ['.github/workflows/ci.yml', '.github/workflows/release.yml']) {
    if (!existsSync(join(root, f))) { errs.push(`${f} missing`); continue; }
    const t = readFileSync(join(root, f), 'utf8');
    if (/password\s*:\s*(?!\$\{\{)[^\s$]+/i.test(t)) errs.push(`${f}: literal password-like value`);
    if (!/permissions:/.test(t)) errs.push(`${f}: no explicit permissions block`);
  }
  result('GitHub workflows: explicit permissions, no literal passwords', errs);
}

process.exit(failures ? 1 : 0);
