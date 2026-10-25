// Scenario: burst SMS above the configured provider rate limit; expect API stays healthy (202) and the
// dispatcher requeues with delay (no attempts consumed, no 5xx). Needs profile local/test (fault-mode endpoint is dev only). NOT executed.
import { sleep } from 'k6';
import { adminToken, senderToken, submit, checkAccepted, setFaultMode, queues, baseThresholds } from './lib/common.js';

const PROVIDER_ID = __ENV.PROVIDER_ID || 'sim-sms';

export const options = {
  scenarios: { burst: { executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s', duration: '2m', preAllocatedVUs: 80, maxVUs: 300 } },
  thresholds: baseThresholds,
};

export function setup() {
  const admin = adminToken();
  // RATE_LIMIT fault mode makes the simulator answer RATE_LIMITED part of the time
  const r = setFaultMode(admin, PROVIDER_ID, 'RATE_LIMIT', 0.3);
  if (r.status >= 300) console.warn(`could not set fault mode (HTTP ${r.status}); continuing with natural provider rate limit only`);
  return { sender: senderToken(), admin };
}

let n = 0;
export default function (d) { n += 1; checkAccepted(submit(d.sender, n + __VU * 100000, { channel: 'SMS', priority: 'NORMAL' })); }

export function teardown(d) {
  sleep(5);
  const q = queues(d.admin);
  console.log(`post-burst /ops/queues: ${JSON.stringify(q)}`);
  setFaultMode(d.admin, PROVIDER_ID, 'NONE', 0);
}
