// Scenario: many operators polling the console dashboards (ops summary, queues, lists). NOT executed.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, operatorToken, auth, SLO } from './lib/common.js';

export const options = {
  scenarios: { operators: { executor: 'ramping-vus', startVUs: 1, stages: [{ target: 30, duration: '1m' }, { target: 30, duration: '4m' }, { target: 0, duration: '30s' }] } },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:ops-summary}': ['p(95)<400'],
    'http_req_duration{name:ops-queues}': ['p(95)<400'],
    'http_req_duration{name:failed-messages}': [`p(95)<${SLO.readP95}`],
    checks: ['rate>0.99'],
  },
};

export function setup() { return { token: operatorToken() }; }

export default function (d) {
  const h = { headers: auth(d.token) };
  const reqs = [
    ['ops-summary', `${BASE_URL}/api/v1/ops/summary`],
    ['ops-queues', `${BASE_URL}/api/v1/ops/queues`],
    ['failed-messages', `${BASE_URL}/api/v1/failed-messages?page=0&size=50`],
    ['dead-letters', `${BASE_URL}/api/v1/dead-letters?page=0&size=50`],
    ['providers', `${BASE_URL}/api/v1/providers`],
  ];
  for (const [name, url] of reqs) {
    const r = http.get(url, { ...h, tags: { name } });
    check(r, { [`${name} 200`]: (x) => x.status === 200 });
  }
  sleep(5);   // the console polls roughly every 5s
}
