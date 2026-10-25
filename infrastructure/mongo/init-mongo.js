// Executed once by the mongo image entrypoint (mongosh). Dummy password comes from MONGO_APP_PASSWORD (.env).
// NOT executed against a real MongoDB on the authoring machine.
const dbName = 'messaging_diagnostics';
const appPassword = process.env.MONGO_APP_PASSWORD;
if (!appPassword) { throw new Error('MONGO_APP_PASSWORD must be set'); }

const d = db.getSiblingDB(dbName);
d.createUser({
  user: 'messaging_app',
  pwd: appPassword,
  roles: [{ role: 'readWrite', db: dbName }]
});

d.createCollection('provider_diagnostics');
// Lookup by message and by provider+time
d.provider_diagnostics.createIndex({ messageId: 1, receivedAt: -1 }, { name: 'ix_message_received' });
d.provider_diagnostics.createIndex({ providerId: 1, receivedAt: -1 }, { name: 'ix_provider_received' });
d.provider_diagnostics.createIndex({ tenantId: 1, receivedAt: -1 }, { name: 'ix_tenant_received' });
// TTL: diagnostics/raw callback archive expires after 30 days (align with messaging.retention.event-days)
d.provider_diagnostics.createIndex({ receivedAt: 1 }, { name: 'ttl_received_30d', expireAfterSeconds: 2592000 });
