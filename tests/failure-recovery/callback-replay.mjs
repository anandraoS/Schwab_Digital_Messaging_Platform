// Scenario 3: duplicate and out-of-order callbacks apply monotonically. Needs a running app (profile local).
import { api, attempts, check, finish, postCallback, submitEmail, tokens, waitFor } from './lib.mjs';

const t = await tokens();
console.log('Scenario 3: duplicate and out-of-order callbacks');
const id = await submitEmail(t.sender);
await waitFor(t.sender, id, 'SENT_TO_PROVIDER', 30_000);
const [attempt] = await attempts(t.sender, id);
const event = (type, eventId, extra = {}) => ({ event: type, message_id: attempt.providerMessageId, event_id: eventId, ...extra });
const run = Date.now();

const delivered = await postCallback('simulated-email', event('delivered', `d-${run}`), `d-${run}`);
check('DELIVERED applied (202 accepted)', delivered.status === 202 && delivered.json.result === 'accepted', delivered.text);
check('status is DELIVERED', (await waitFor(t.sender, id, 'DELIVERED', 5_000)) === 'DELIVERED');

const late = await postCallback('simulated-email', event('sent', `s-${run}`, { timestamp: new Date(Date.now() - 30_000).toISOString() }), `s-${run}`);
check('late SENT is out_of_order and ignored', late.status === 202 && late.json.result === 'out_of_order', late.text);

const replay = await postCallback('simulated-email', event('delivered', `d-${run}`), `d-${run}`);
check('same X-Event-Id is a replay (200)', replay.status === 200 && replay.json.result === 'replayed', replay.text);

const stale = await postCallback('simulated-email', event('delivered', `d2-${run}`), `d2-${run}`);
check('second DELIVERED under a new id is stale', stale.json?.result === 'stale', stale.text);

const bounced = await postCallback('simulated-email', event('hard_bounce', `b-${run}`), `b-${run}`);
check('BOUNCED after DELIVERED is a conflict', bounced.json?.result === 'conflict', bounced.text);
check('status still DELIVERED (first terminal wins)', (await waitFor(t.sender, id, 'DELIVERED', 5_000)) === 'DELIVERED');

const bad = await api('POST', '/api/v1/callbacks/simulated-email', {
  body: JSON.stringify(event('delivered', `x-${run}`)),
  headers: { 'X-Signature': 't=1,v1=00', 'X-Provider-Key-Id': 'simulated-email-key', 'X-Event-Id': `x-${run}` },
});
check('forged signature is rejected (401 INVALID_SIGNATURE)', bad.status === 401 && bad.json.code === 'INVALID_SIGNATURE', bad.text);

const events = await api('GET', `/api/v1/delivery-events?messageId=${id}&size=50`, { token: t.operator });
check('delivery events recorded with applied flags', events.json.content.some((e) => e.type === 'DELIVERED' && e.applied) &&
  events.json.content.some((e) => e.type === 'SENT' && !e.applied && e.ignoredReason === 'OUT_OF_ORDER'));
finish('callback-replay');
