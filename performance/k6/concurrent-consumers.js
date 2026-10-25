// Scenario: steady producer load while sampling consumer concurrency/backlog. NOT executed.
// Run it multiple times with different messaging.dispatch.concurrency settings (MESSAGING_DISPATCH_CONCURRENCY) and compare drain.
import { sleep } from 'k6';
import { Trend, Gauge } from 'k6/metrics';
import { senderToken, operatorToken, submit, checkAccepted, queues, baseThresholds } from './lib/common.js';

const depth = new Gauge('mp_queue_depth_sample');
const inflight = new Gauge('mp_inflight_sample');
const sampleLatency = new Trend('mp_ops_queues_ms', true);

export const options = {
  scenarios: {
    producers: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '5m', preAllocatedVUs: 60, maxVUs: 200, exec: 'produce' },
    sampler: { executor: 'constant-vus', vus: 1, duration: '5m30s', exec: 'sample' },
  },
  thresholds: { ...baseThresholds, mp_queue_depth_sample: ['value<5000'] },
};

export function setup() { return { sender: senderToken(), operator: operatorToken() }; }

let n = 0;
export function produce(d) { n += 1; checkAccepted(submit(d.sender, n + __VU * 100000)); }

export function sample(d) {
  const t0 = Date.now();
  const q = queues(d.operator);
  sampleLatency.add(Date.now() - t0);
  if (q) {
    // Field names per CONTRACT 5 /ops/queues: depth per priority, in-flight, consumers, DLQ depth, ...
    const total = (q.depthByPriority ? Object.values(q.depthByPriority).reduce((a, b) => a + b, 0) : q.depth) || 0;
    depth.add(total); inflight.add(q.inFlight || q.inflight || 0);
  }
  sleep(5);
}
