// Scenario: high-volume API submission (ramping arrival rate). NOT executed.
// k6 run -e BASE_URL=http://localhost:18080 performance/k6/submit-high-volume.js
import { sleep } from 'k6';
import { senderToken, submit, checkAccepted, baseThresholds } from './lib/common.js';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 400,
      stages: [
        { target: 50, duration: '1m' }, { target: 150, duration: '3m' }, { target: 150, duration: '5m' }, { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds: baseThresholds,
};

export function setup() { return { token: senderToken() }; }

let counter = 0;
export default function (data) {
  counter += 1;
  const res = submit(data.token, counter + __VU * 100000);
  // 429 (API rate limit) and 503 (backpressure) are legitimate protective responses; surfaced as separate counts via tags in output.
  checkAccepted(res);
  if (res.status === 503 || res.status === 429) sleep(parseFloat(res.headers['Retry-After'] || '1'));
}
