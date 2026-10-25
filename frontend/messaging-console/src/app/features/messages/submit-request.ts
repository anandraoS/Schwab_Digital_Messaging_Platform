import { Category, Channel, Priority, SubmitMessageRequest } from '../../core/models/api.models';

export interface SubmitFormValue {
  clientReference: string;
  recipientId: string;
  externalRef: string;
  channel: Channel;
  category: Category;
  priority: Priority;
  templateCode: string;
  locale: string;
  templateVersion: number | null;
  variables: { key: string; value: string }[];
  email: { replyTo: string };
  sms: { senderId: string };
  push: { collapseKey: string; ttlSeconds: number | null };
  fallbackChannels: Channel[];
  schedule: { sendAt: string; expiresAt: string };
}

function iso(local: string): string | undefined {
  if (!local) {
    return undefined;
  }
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

/** Maps the form value to the CONTRACT SubmitMessageRequest, omitting empty/irrelevant parts. */
export function buildSubmitRequest(v: SubmitFormValue): SubmitMessageRequest {
  const req: SubmitMessageRequest = {
    channel: v.channel,
    category: v.category,
    priority: v.priority,
    template: { code: v.templateCode.trim(), locale: v.locale.trim(), version: v.templateVersion ?? null },
  };
  if (v.clientReference.trim()) {
    req.clientReference = v.clientReference.trim();
  }
  if (v.recipientId.trim()) {
    req.recipientId = v.recipientId.trim();
  } else if (v.externalRef.trim()) {
    req.recipient = { externalRef: v.externalRef.trim() };
  }
  const vars: Record<string, string> = {};
  for (const row of v.variables) {
    if (row.key.trim()) {
      vars[row.key.trim()] = row.value;
    }
  }
  if (Object.keys(vars).length) {
    req.variables = vars;
  }
  if (v.channel === 'EMAIL' && v.email.replyTo.trim()) {
    req.channelOptions = { email: { replyTo: v.email.replyTo.trim() } };
  } else if (v.channel === 'SMS' && v.sms.senderId.trim()) {
    req.channelOptions = { sms: { senderId: v.sms.senderId.trim() } };
  } else if (v.channel === 'PUSH' && (v.push.collapseKey.trim() || v.push.ttlSeconds)) {
    req.channelOptions = {
      push: {
        ...(v.push.collapseKey.trim() ? { collapseKey: v.push.collapseKey.trim() } : {}),
        ...(v.push.ttlSeconds ? { ttlSeconds: Number(v.push.ttlSeconds) } : {}),
      },
    };
  }
  if (v.fallbackChannels.length) {
    req.fallbackChannels = v.fallbackChannels;
  }
  const sendAt = iso(v.schedule.sendAt);
  const expiresAt = iso(v.schedule.expiresAt);
  if (sendAt || expiresAt) {
    req.schedule = { ...(sendAt ? { sendAt } : {}), ...(expiresAt ? { expiresAt } : {}) };
  }
  return req;
}
