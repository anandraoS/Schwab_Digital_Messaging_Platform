-- Stage 2 (messaging core) indexes for the dispatch pipeline queries. Plain portable SQL (H2 and PostgreSQL).

-- callback lookup: provider message id -> attempt -> message
CREATE INDEX idx_attempt_provider_msg ON message_attempt (provider_id, provider_message_id);

-- StuckMessageSweeper: PROCESSING with an expired lock
CREATE INDEX idx_msg_status_locked ON message_request (status, locked_until);

-- sweeper (SENT_TO_PROVIDER past the receipt timeout), reconciliation (DELIVERY_UNKNOWN older than reconcile-after),
-- poller (orphaned VALIDATED messages)
CREATE INDEX idx_msg_status_changed ON message_request (status, status_changed_at);

-- reconciliation bookkeeping: count and latest RECONCILIATION_CHECK per message
CREATE INDEX idx_audit_entity_action ON audit_event (entity_type, entity_id, action, occurred_at);

-- OutboxPublisher / BackpressureGuard: due NEW rows per aggregate
CREATE INDEX idx_outbox_aggregate_status ON outbox_event (aggregate_id, status);
