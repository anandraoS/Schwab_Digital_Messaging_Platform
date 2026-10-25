// Scenario 4: provider timeout moves the message to DELIVERY_UNKNOWN without a blind resend. Needs a running app (profile local).
// The default provider timeout is 2 s. Reconciliation runs after messaging.dispatch.reconcile-after (default 5 min); set
// WAIT_FOR_RECONCILIATION=1 to wait for it, or start the app with --messaging.dispatch.reconcile-after=5s.
import { api, attempts, check, finish, setFault, submitEmail, tokens, waitFor } from './lib.mjs';

const t = await tokens();
console.log('Scenario 4: provider timeout / ambiguous result');
try {
  check('fault mode TIMEOUT accepted', (await setFault(t.admin, 'simulated-email', 'TIMEOUT')).status === 200);
  const id = await submitEmail(t.sender);
  check('message becomes DELIVERY_UNKNOWN (not FAILED)', (await waitFor(t.sender, id, 'DELIVERY_UNKNOWN', 30_000)) === 'DELIVERY_UNKNOWN');
  await new Promise((r) => setTimeout(r, 3_000));
  const tried = await attempts(t.sender, id);
  check('exactly one provider attempt, outcome TIMEOUT, no blind retry', tried.length === 1 && tried[0].outcome === 'TIMEOUT', JSON.stringify(tried));
  const failed = await api('GET', '/api/v1/failed-messages?size=200', { token: t.operator });
  check('visible in /failed-messages for operators', failed.json.content.some((m) => m.id === id && m.status === 'DELIVERY_UNKNOWN'));

  await setFault(t.admin, 'simulated-email', 'NONE');
  if (process.env.WAIT_FOR_RECONCILIATION === '1') {
    console.log('  waiting for the reconciliation pass ...');
    check('reconciliation finds NOT_RECEIVED and re-sends once', (await waitFor(t.sender, id, 'SENT_TO_PROVIDER', 8 * 60_000)) === 'SENT_TO_PROVIDER');
    check('second attempt recorded', (await attempts(t.sender, id)).length === 2);
  } else {
    console.log('  (set WAIT_FOR_RECONCILIATION=1 to also wait for the reconciliation pass)');
  }
} finally {
  await setFault(t.admin, 'simulated-email', 'NONE');
}
finish('provider-timeout');
