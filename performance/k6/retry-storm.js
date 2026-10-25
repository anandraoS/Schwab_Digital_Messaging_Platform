// Scenario: provider returns TRANSIENT failures for everything; verify bounded retries (backoff + jitter), no thundering herd,
// API still healthy, and the system recovers when the fault is cleared. Needs profile local/test. NOT executed.
import { sleep } from 'k6';
import { adminToken, senderToken, submit, checkAccepted, setFaultMode, queues, baseThresholds } from './lib/common.js';

const PROVIDER_ID = __ENV.PROVIDER_ID || 'sim-email';

export const options = {
  scenarios: { storm: { executor: 'constant-arrival-rate', rate: 60, timeUnit: '1s', duration: '3m', preAllocatedVUs: 40, maxVUs: 150 } },
  thresholds: baseThresholds,
};

export function setup() {
  const admin = adminToken();
  const r = setFaultMode(admin, PROVIDER_ID, 'TRANSIENT', 1.0);
  if (r.status >= 300) console.warn(`fault-mode HTTP ${r.status}; storm will not be induced`);
  return { sender: senderToken(), admin };
}

let n = 0;
export default function (d) { n += 1; checkAccepted(submit(d.sender, n + __VU * 100000, { channel: 'EMAIL', priority: 'NORMAL' })); }

export function teardown(d) {
  console.log(`during-storm /ops/queues: ${JSON.stringify(queues(d.admin))}`);
  setFaultMode(d.admin, PROVIDER_ID, 'NONE', 0);   // clear fault; then watch retry rate and DLQ in Grafana
  sleep(30);
  console.log(`after-recovery /ops/queues: ${JSON.stringify(queues(d.admin))}`);
}
