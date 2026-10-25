#!/usr/bin/env node
// Generates the Grafana dashboard JSON files under observability/grafana/dashboards from compact definitions.
// Run: node observability/grafana/build-dashboards.mjs   (output is committed; Grafana rendering NOT verified here)
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const out = resolve(dirname(fileURLToPath(import.meta.url)), 'dashboards');
mkdirSync(out, { recursive: true });
const DS = { type: 'prometheus', uid: 'prometheus' };

function panel(id, title, exprs, x, y, { type = 'timeseries', unit = 'short', w = 12, h = 8, legends = [] } = {}) {
  return {
    id, title, type, datasource: DS,
    gridPos: { x, y, w, h },
    fieldConfig: { defaults: { unit }, overrides: [] },
    options: type === 'stat' ? { reduceOptions: { calcs: ['lastNotNull'] } } : { legend: { displayMode: 'list', placement: 'bottom' } },
    targets: exprs.map((e, i) => ({ refId: String.fromCharCode(65 + i), expr: e, legendFormat: legends[i] ?? '', datasource: DS })),
  };
}

function dashboard(uid, title, panelDefs) {
  const panels = panelDefs.map((p, i) => panel(i + 1, p.title, p.exprs, (i % 2) * 12, Math.floor(i / 2) * 8, p));
  return {
    uid, title, schemaVersion: 39, version: 1, editable: false, refresh: '30s', timezone: 'utc',
    tags: ['messaging-platform'], time: { from: 'now-1h', to: 'now' }, templating: { list: [] }, annotations: { list: [] }, panels,
  };
}

const dashboards = {
  'api-slo.json': dashboard('mp-api-slo', 'API and SLO', [
    { title: 'Request rate (/api)', exprs: ['sli:api_requests:rate5m'], unit: 'reqps' },
    { title: '5xx error ratio (SLO 0.1%)', exprs: ['sli:api_error_ratio:rate5m', 'sli:api_error_ratio:rate1h'], unit: 'percentunit', legends: ['5m', '1h'] },
    { title: 'p95 latency (SLO 300ms)', exprs: ['sli:api_latency_p95_seconds:rate5m'], unit: 's' },
    { title: 'Requests by status', exprs: ['sum by (status) (rate(http_server_requests_seconds_count{uri=~"/api/.*"}[5m]))'], unit: 'reqps', legends: ['{{status}}'] },
    { title: 'Messages accepted by priority', exprs: ['sum by (priority) (rate(messaging_messages_accepted_total[5m]))'], unit: 'ops', legends: ['{{priority}}'] },
    { title: 'API rate-limit rejections', exprs: ['sum by (scope) (rate(messaging_ratelimit_rejected_total[5m]))'], unit: 'ops', legends: ['{{scope}}'] },
    { title: 'Idempotency hits', exprs: ['rate(messaging_idempotency_hits_total[5m])'], unit: 'ops' },
    { title: 'Callbacks by result', exprs: ['sum by (result) (rate(messaging_callbacks_total[5m]))'], unit: 'ops', legends: ['{{result}}'] },
  ]),
  'dispatch-queues.json': dashboard('mp-dispatch-queues', 'Dispatch and Queues', [
    { title: 'Queue depth by priority', exprs: ['messaging_queue_depth'], legends: ['{{priority}}'] },
    { title: 'In-flight messages', exprs: ['messaging_queue_inflight'] },
    { title: 'DLQ depth', exprs: ['messaging_queue_dlq_depth'], type: 'stat' },
    { title: 'Dead-letters by reason', exprs: ['sum by (reason) (increase(messaging_deadletter_total[15m]))'], legends: ['{{reason}}'] },
    { title: 'Retries scheduled by channel', exprs: ['sum by (channel) (rate(messaging_retry_scheduled_total[5m]))'], unit: 'ops', legends: ['{{channel}}'] },
    { title: 'Dispatch delay p95 (accepted to provider)', exprs: ['sli:dispatch_delay_p95_seconds:rate5m'], unit: 's' },
    { title: 'State transitions to', exprs: ['sum by (to) (rate(messaging_transitions_total[5m]))'], unit: 'ops', legends: ['{{to}}'] },
    { title: 'DELIVERY_UNKNOWN transitions (15m)', exprs: ['sum(increase(messaging_transitions_total{to="DELIVERY_UNKNOWN"}[15m]))'], type: 'stat' },
  ]),
  'providers.json': dashboard('mp-providers', 'Providers', [
    { title: 'Calls by outcome', exprs: ['sum by (provider, outcome) (rate(messaging_provider_calls_total[5m]))'], unit: 'ops', legends: ['{{provider}} {{outcome}}'] },
    { title: 'Failure ratio by provider', exprs: ['sli:provider_failure_ratio:rate5m'], unit: 'percentunit', legends: ['{{provider}}'] },
    { title: 'Latency p95 by provider', exprs: ['sli:provider_latency_p95_seconds:rate5m'], unit: 's', legends: ['{{provider}}'] },
    { title: 'Circuit state (0 closed, 1 half-open, 2 open)', exprs: ['messaging_provider_circuit_state'], legends: ['{{provider}}'] },
    { title: 'Rate-limited calls', exprs: ['sum by (provider) (rate(messaging_provider_calls_total{outcome="RATE_LIMITED"}[5m]))'], unit: 'ops', legends: ['{{provider}}'] },
    { title: 'Diagnostics write failures (Mongo)', exprs: ['rate(messaging_diagnostics_write_failures_total[5m])'], unit: 'ops' },
  ]),
  'bulk-batch.json': dashboard('mp-bulk-batch', 'Bulk and Batch', [
    { title: 'Bulk job progress (0..1)', exprs: ['messaging_bulk_progress'], unit: 'percentunit', legends: ['{{jobId}}'] },
    { title: 'Progress change over 5m', exprs: ['delta(messaging_bulk_progress[5m])'], unit: 'percentunit', legends: ['{{jobId}}'] },
    { title: 'Accepted message rate (bulk drives this)', exprs: ['sli:messages_accepted:rate5m'], unit: 'ops' },
    { title: 'Queue depth total', exprs: ['sli:queue_depth:total'] },
    { title: 'Redis fallback events', exprs: ['increase(messaging_redis_fallback_total[5m])'] },
    { title: 'Retries scheduled', exprs: ['sum(rate(messaging_retry_scheduled_total[5m]))'], unit: 'ops' },
  ]),
  'jvm-db-pool.json': dashboard('mp-jvm-db', 'JVM and DB Pool', [
    { title: 'Hikari connections', exprs: ['hikaricp_connections_active', 'hikaricp_connections_idle', 'hikaricp_connections_pending', 'hikaricp_connections_max'], legends: ['active', 'idle', 'pending', 'max'] },
    { title: 'Hikari acquire time p95', exprs: ['histogram_quantile(0.95, sum by (le) (rate(hikaricp_connections_acquire_seconds_bucket[5m])))'], unit: 's' },
    { title: 'JVM heap used', exprs: ['sum by (instance) (jvm_memory_used_bytes{area="heap"})'], unit: 'bytes', legends: ['{{instance}}'] },
    { title: 'GC pause time share', exprs: ['sum by (instance) (rate(jvm_gc_pause_seconds_sum[5m]))'], unit: 'percentunit', legends: ['{{instance}}'] },
    { title: 'Live threads', exprs: ['jvm_threads_live_threads'], legends: ['{{instance}}'] },
    { title: 'Process CPU', exprs: ['process_cpu_usage'], unit: 'percentunit', legends: ['{{instance}}'] },
  ]),
};

for (const [file, d] of Object.entries(dashboards)) {
  writeFileSync(resolve(out, file), JSON.stringify(d, null, 2) + '\n');
  console.log(`wrote ${file} (${d.panels.length} panels)`);
}
