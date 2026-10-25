// ER diagrams for PDF 1 (all 19 application tables): one overview plus three detail figures.
const cap = (text, impl) => `${text} Implementation: ${impl}`;
const col = (name, type, flag) => ({ name, type, ...(flag === 'pk' ? { pk: true } : {}), ...(flag === 'fk' ? { fk: true } : {}) });
const ERCAP = 'backend/src/main/resources/db/migration/V1__tenant_recipient.sql ... V200__bulk_ledger_legal_hold.sql';
const T = (name, ...cols) => ({ name, columns: cols });
const pk = (n, t = 'VARCHAR(36)') => col(n, t, 'pk');
const fk = (n, t = 'VARCHAR(36)') => col(n, t, 'fk');

export const ER_DIAGRAMS = {};

ER_DIAGRAMS.erOverview = {
  type: 'er',
  columns: 4,
  caption: cap('ER overview: all 19 application tables created by Flyway migrations V1 to V200 with their foreign-key relationships (columns abbreviated to keys; the next three figures show the details). Spring Batch metadata tables from V100 are not drawn.', ERCAP),
  entities: [
    T('tenant', pk('id')), T('recipient', pk('id'), fk('tenant_id')), T('recipient_preference', pk('recipient_id')), T('suppression', pk('id'), fk('tenant_id')),
    T('template', pk('id'), fk('tenant_id')), T('template_version', pk('id'), fk('template_id')), T('provider_config', pk('id')),
    T('message_request', pk('id'), fk('tenant_id'), fk('recipient_id'), fk('bulk_job_id')), T('message_attempt', pk('id'), fk('message_id')), T('delivery_event', pk('id'), fk('message_id')),
    T('bulk_job', pk('id'), fk('tenant_id')), T('bulk_job_failure', fk('job_id')), T('bulk_job_row', pk('job_id')),
    T('audit_event', pk('id')), T('idempotency_record', pk('id')), T('outbox_event', pk('id')), T('processed_message', pk('dispatch_id', 'VARCHAR')), T('replay_guard', pk('event_id', 'VARCHAR')), T('legal_hold', pk('id')),
  ],
  relations: [
    { from: 'tenant', to: 'recipient', fromCard: '1', toCard: '0..N' },
    { from: 'recipient', to: 'recipient_preference', fromCard: '1', toCard: '0..1' },
    { from: 'tenant', to: 'suppression', fromCard: '1', toCard: '0..N' },
    { from: 'tenant', to: 'template', fromCard: '1', toCard: '0..N' },
    { from: 'template', to: 'template_version', fromCard: '1', toCard: '1..N' },
    { from: 'recipient', to: 'message_request', fromCard: '1', toCard: '0..N' },
    { from: 'message_request', to: 'message_attempt', fromCard: '1', toCard: '0..N' },
    { from: 'message_request', to: 'delivery_event', fromCard: '1', toCard: '0..N' },
    { from: 'tenant', to: 'bulk_job', fromCard: '1', toCard: '0..N' },
    { from: 'bulk_job', to: 'message_request', fromCard: '0..1', toCard: '0..N' },
    { from: 'bulk_job', to: 'bulk_job_failure', fromCard: '1', toCard: '0..N' },
    { from: 'bulk_job', to: 'bulk_job_row', fromCard: '1', toCard: '0..N' },
  ],
};

ER_DIAGRAMS.erIdentity = {
  type: 'er',
  caption: cap('ER detail 1 of 3: tenants, recipients, preferences, suppressions and templates. UNIQUE(tenant_id, external_ref) on recipient, UNIQUE(tenant_id, channel, address_hash) on suppression, UNIQUE(tenant_id, code, channel) on template, UNIQUE(template_id, version_no, locale) on template_version.', 'backend/src/main/resources/db/migration/V1__tenant_recipient.sql, V2__templates.sql'),
  entities: [
    T('tenant', pk('id'), col('name', 'VARCHAR'), col('status', 'VARCHAR')),
    T('recipient', pk('id'), fk('tenant_id'), col('external_ref', 'VARCHAR'), col('email', 'VARCHAR'), col('phone_e164', 'VARCHAR'), col('push_token', 'VARCHAR'), col('locale', 'VARCHAR'), col('time_zone', 'VARCHAR'), col('deleted', 'BOOLEAN'), col('version', 'BIGINT')),
    T('recipient_preference', pk('recipient_id'), col('marketing_opt_in', 'BOOLEAN'), col('email_enabled', 'BOOLEAN'), col('sms_enabled', 'BOOLEAN'), col('push_enabled', 'BOOLEAN'), col('quiet_start', 'VARCHAR'), col('quiet_end', 'VARCHAR'), col('fallback_order', 'VARCHAR'), col('version', 'BIGINT')),
    T('suppression', pk('id'), fk('tenant_id'), col('channel', 'VARCHAR'), col('address_hash', 'VARCHAR'), col('reason', 'VARCHAR')),
    T('template', pk('id'), fk('tenant_id'), col('code', 'VARCHAR'), col('channel', 'VARCHAR'), col('category', 'VARCHAR')),
    T('template_version', pk('id'), fk('template_id'), col('version_no', 'INT'), col('locale', 'VARCHAR'), col('status', 'VARCHAR'), col('subject', 'VARCHAR'), col('body', 'VARCHAR'), col('created_by', 'VARCHAR'), col('approved_by', 'VARCHAR'), col('published_at', 'TIMESTAMP')),
  ],
  relations: [
    { from: 'tenant', to: 'recipient', fromCard: '1', toCard: '0..N', label: 'owns' },
    { from: 'recipient', to: 'recipient_preference', fromCard: '1', toCard: '0..1', label: 'has' },
    { from: 'tenant', to: 'suppression', fromCard: '1', toCard: '0..N', label: 'scopes' },
    { from: 'tenant', to: 'template', fromCard: '1', toCard: '0..N', label: 'owns' },
    { from: 'template', to: 'template_version', fromCard: '1', toCard: '1..N', label: 'versions' },
  ],
};

ER_DIAGRAMS.erMessaging = {
  type: 'er',
  caption: cap('ER detail 2 of 3: the message and what hangs off it. UNIQUE(tenant_id, idempotency_key) on message_request, UNIQUE(message_id, attempt_no) on message_attempt, UNIQUE(provider_id, provider_event_id) on delivery_event. The template is referenced logically by (template_code, template_version_no, template_locale).', 'backend/src/main/resources/db/migration/V3__messages.sql'),
  entities: [
    T('recipient', pk('id'), fk('tenant_id')),
    T('message_request', pk('id'), fk('tenant_id'), fk('recipient_id'), fk('bulk_job_id'), col('channel', 'VARCHAR'), col('category', 'VARCHAR'), col('priority', 'VARCHAR'), col('status', 'VARCHAR'), col('template_code', 'VARCHAR'), col('template_version_no', 'INT'), col('idempotency_key', 'VARCHAR'), col('attempt_count', 'INT'), col('max_attempts', 'INT'), col('next_attempt_at', 'TIMESTAMP'), col('locked_until', 'TIMESTAMP'), col('version', 'BIGINT')),
    T('message_attempt', pk('id'), fk('message_id'), col('attempt_no', 'INT'), col('channel', 'VARCHAR'), col('provider_id', 'VARCHAR'), col('state', 'VARCHAR'), col('outcome', 'VARCHAR'), col('provider_message_id', 'VARCHAR'), col('latency_ms', 'BIGINT')),
    T('delivery_event', pk('id'), fk('message_id'), col('provider_id', 'VARCHAR'), col('provider_event_id', 'VARCHAR'), col('type', 'VARCHAR'), col('occurred_at', 'TIMESTAMP'), col('applied', 'BOOLEAN'), col('ignored_reason', 'VARCHAR')),
    T('provider_config', pk('id'), col('provider_id', 'VARCHAR'), col('channel', 'VARCHAR'), col('enabled', 'BOOLEAN'), col('priority_order', 'INT'), col('rate_limit_per_sec', 'INT'), col('timeout_ms', 'INT'), col('hmac_key_ref', 'VARCHAR')),
    T('bulk_job', pk('id'), fk('tenant_id'), col('status', 'VARCHAR')),
  ],
  relations: [
    { from: 'recipient', to: 'message_request', fromCard: '1', toCard: '0..N', label: 'receives' },
    { from: 'message_request', to: 'message_attempt', fromCard: '1', toCard: '0..N', label: 'attempts' },
    { from: 'message_request', to: 'delivery_event', fromCard: '1', toCard: '0..N', label: 'receipts' },
    { from: 'bulk_job', to: 'message_request', fromCard: '0..1', toCard: '0..N', label: 'bulk_job_id' },
    { from: 'provider_config', to: 'delivery_event', fromCard: '1', toCard: '0..N', label: 'provider_id' },
    { from: 'provider_config', to: 'message_attempt', fromCard: '1', toCard: '0..N', label: 'provider_id' },
  ],
};

ER_DIAGRAMS.erSupport = {
  type: 'er',
  caption: cap('ER detail 3 of 3: bulk jobs and their ledgers, plus the reliability and compliance tables (outbox, idempotency, dedupe, replay guard, audit, legal hold). outbox_event.aggregate_id and processed_message.dispatch_id are logical references only.', 'backend/src/main/resources/db/migration/V4__bulk_audit_idempotency_outbox.sql, V200__bulk_ledger_legal_hold.sql'),
  entities: [
    T('tenant', pk('id')),
    T('bulk_job', pk('id'), fk('tenant_id'), col('name', 'VARCHAR'), col('status', 'VARCHAR'), col('total', 'BIGINT'), col('processed', 'BIGINT'), col('succeeded', 'BIGINT'), col('failed', 'BIGINT'), col('duplicate', 'BIGINT'), col('spring_batch_execution_id', 'BIGINT')),
    T('bulk_job_failure', fk('job_id'), col('line_no', 'BIGINT'), col('reason', 'VARCHAR'), col('raw_line_hash', 'VARCHAR')),
    T('bulk_job_row', pk('job_id'), pk('external_ref', 'VARCHAR'), col('line_no', 'BIGINT')),
    T('audit_event', pk('id'), col('tenant_id', 'VARCHAR(36)'), col('actor', 'VARCHAR'), col('action', 'VARCHAR'), col('entity_type', 'VARCHAR'), col('entity_id', 'VARCHAR'), col('occurred_at', 'TIMESTAMP')),
    T('idempotency_record', pk('id'), col('tenant_id', 'VARCHAR(36)'), col('idem_key', 'VARCHAR'), col('request_hash', 'VARCHAR'), col('status', 'VARCHAR'), col('message_id', 'VARCHAR(36)')),
    T('outbox_event', pk('id'), col('aggregate_id', 'VARCHAR'), col('topic', 'VARCHAR'), col('status', 'VARCHAR'), col('available_at', 'TIMESTAMP')),
    T('processed_message', pk('dispatch_id', 'VARCHAR'), col('processed_at', 'TIMESTAMP')),
    T('replay_guard', pk('event_id', 'VARCHAR'), col('seen_at', 'TIMESTAMP')),
    T('legal_hold', pk('id'), col('tenant_id', 'VARCHAR(36)'), col('scope', 'VARCHAR'), col('entity_id', 'VARCHAR'), col('released_at', 'TIMESTAMP')),
  ],
  relations: [
    { from: 'tenant', to: 'bulk_job', fromCard: '1', toCard: '0..N', label: 'owns' },
    { from: 'bulk_job', to: 'bulk_job_failure', fromCard: '1', toCard: '0..N' },
    { from: 'bulk_job', to: 'bulk_job_row', fromCard: '1', toCard: '0..N' },
  ],
};
