// Scenario 1: provider outage, retries with backoff, dead letter, operator replay after recovery.
// Needs a running app (profile local). With the default backoff (base 1 s, cap 60 s, 5 attempts) this takes about a minute.
// Faster: start the app with --messaging.dispatch.backoff-base-ms=100 --messaging.dispatch.backoff-cap-ms=1000
import { api, attempts, check, finish, setFault, submitEmail, tokens, waitFor } from './lib.mjs';

const t = await tokens();
console.log('Scenario 1: provider outage then recovery');
try {
  check('fault mode TRANSIENT accepted', (await setFault(t.admin, 'simulated-email', 'TRANSIENT')).status === 200);
  const id = await submitEmail(t.sender);
  console.log(`  submitted ${id}; waiting for retries to be exhausted ...`);
  check('dead-lettered after the retries', (await waitFor(t.sender, id, 'DEAD_LETTERED', 180_000)) === 'DEAD_LETTERED');
  const tried = await attempts(t.sender, id);
  check('one attempt row per try (5 by default)', tried.length >= 3, `got ${tried.length}`);
  check('every attempt was a transient failure', tried.every((a) => a.outcome === 'TRANSIENT_FAILURE'));
  const dlq = await api('GET', '/api/v1/dead-letters?size=200', { token: t.operator });
  check('listed in /dead-letters with the reason', dlq.json.content.some((d) => d.messageId === id && d.reason === 'RETRIES_EXHAUSTED'));

  check('provider recovers', (await setFault(t.admin, 'simulated-email', 'NONE')).status === 200);
  const replay = await api('POST', `/api/v1/dead-letters/${id}/replay`, { token: t.operator });
  check('operator replay accepted', replay.status === 200, replay.text);
  check('replayed message reaches the provider', (await waitFor(t.sender, id, 'SENT_TO_PROVIDER', 60_000)) === 'SENT_TO_PROVIDER');
} finally {
  await setFault(t.admin, 'simulated-email', 'NONE');
}
finish('provider-outage');
