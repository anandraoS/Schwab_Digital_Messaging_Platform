// Layout hints (grid positions) for the ER diagrams, applied on top of diagrams-er.mjs.
import { ER_DIAGRAMS } from './diagrams-er.mjs';

const apply = (key, positions, dropRelations = []) => {
  const d = ER_DIAGRAMS[key];
  for (const e of d.entities) if (positions[e.name]) e.pos = positions[e.name];
  if (dropRelations.length) d.relations = d.relations.filter((r) => !dropRelations.includes(`${r.from}>${r.to}`));
};

apply('erOverview', {
  tenant: [0, 0], recipient: [1, 0], recipient_preference: [2, 0], suppression: [3, 0],
  template: [0, 1], message_request: [1, 1], message_attempt: [2, 1], provider_config: [3, 1],
  template_version: [0, 2], delivery_event: [1, 2], bulk_job: [2, 2], bulk_job_failure: [3, 2],
  audit_event: [0, 3], idempotency_record: [1, 3], bulk_job_row: [2, 3], outbox_event: [3, 3],
  processed_message: [0, 4], replay_guard: [1, 4], legal_hold: [2, 4],
}, ['tenant>suppression', 'tenant>bulk_job']);

apply('erIdentity', {
  tenant: [1, 0], recipient: [0, 1], suppression: [1, 1], template: [2, 1], recipient_preference: [0, 2], template_version: [2, 2],
});

apply('erMessaging', {
  recipient: [0, 0], bulk_job: [2, 0], message_request: [1, 1], message_attempt: [0, 2], provider_config: [1, 2], delivery_event: [2, 2],
});

apply('erSupport', {
  tenant: [0, 0], bulk_job: [1, 0], bulk_job_failure: [2, 0], audit_event: [0, 1], bulk_job_row: [1, 1], idempotency_record: [2, 1],
  outbox_event: [0, 2], processed_message: [1, 2], replay_guard: [2, 2], legal_hold: [0, 3],
});

for (const k of ['erIdentity', 'erMessaging', 'erSupport']) ER_DIAGRAMS[k].columns = 3;
