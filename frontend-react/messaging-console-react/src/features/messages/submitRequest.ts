import {
  MAX_PUSH_TTL_SECONDS, expiryAfterSend, fallbackExcludesPrimary, futureDateError, hasUniqueKeys, isEmail,
  isIntegerInRange, isLocale, isSmsSenderId, isVariableKey, oneOf,
} from '../../core/forms/validators';
import type { Category, Channel, Priority, SubmitMessageRequest } from '../../core/models';

export interface SubmitFormValue {
  clientReference: string;
  recipientId: string;
  externalRef: string;
  channel: Channel;
  category: Category;
  priority: Priority;
  templateCode: string;
  locale: string;
  templateVersion: string;
  variables: { key: string; value: string }[];
  replyTo: string;
  senderId: string;
  collapseKey: string;
  ttlSeconds: string;
  fallbackChannels: Channel[];
  sendAt: string;
  expiresAt: string;
}

export const EMPTY_SUBMIT_FORM: SubmitFormValue = {
  clientReference: '', recipientId: '', externalRef: '', channel: 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL',
  templateCode: '', locale: 'en-US', templateVersion: '', variables: [], replyTo: '', senderId: '', collapseKey: '',
  ttlSeconds: '', fallbackChannels: [], sendAt: '', expiresAt: '',
};

function iso(local: string): string | undefined {
  if (!local) {
    return undefined;
  }
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

/** Maps the form value to the CONTRACT SubmitMessageRequest, omitting empty/irrelevant parts. */
export function buildSubmitRequest(v: SubmitFormValue): SubmitMessageRequest {
  const version = v.templateVersion.trim();
  const req: SubmitMessageRequest = {
    channel: v.channel,
    category: v.category,
    priority: v.priority,
    template: { code: v.templateCode.trim(), locale: v.locale.trim(), version: version ? Number(version) : null },
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
  if (v.channel === 'EMAIL' && v.replyTo.trim()) {
    req.channelOptions = { email: { replyTo: v.replyTo.trim() } };
  } else if (v.channel === 'SMS' && v.senderId.trim()) {
    req.channelOptions = { sms: { senderId: v.senderId.trim() } };
  } else if (v.channel === 'PUSH' && (v.collapseKey.trim() || v.ttlSeconds.trim())) {
    req.channelOptions = {
      push: {
        ...(v.collapseKey.trim() ? { collapseKey: v.collapseKey.trim() } : {}),
        ...(v.ttlSeconds.trim() ? { ttlSeconds: Number(v.ttlSeconds) } : {}),
      },
    };
  }
  if (v.fallbackChannels.length) {
    req.fallbackChannels = v.fallbackChannels;
  }
  const sendAt = iso(v.sendAt);
  const expiresAt = iso(v.expiresAt);
  if (sendAt || expiresAt) {
    req.schedule = { ...(sendAt ? { sendAt } : {}), ...(expiresAt ? { expiresAt } : {}) };
  }
  return req;
}

export type SubmitErrors = Partial<Record<
  'recipient' | 'fallback' | 'replyTo' | 'senderId' | 'ttlSeconds' | 'templateCode' | 'locale' | 'templateVersion' | 'variables'
  | 'variableKeys' | 'sendAt' | 'expiry', string>>;

/** Same rules as the Angular form validators. Returns an empty object when the form is valid. */
export function validateSubmit(v: SubmitFormValue, now: () => number = Date.now): SubmitErrors {
  const e: SubmitErrors = {};
  if (!oneOf(v.recipientId, v.externalRef)) e.recipient = 'Provide a recipient id or an external reference.';
  if (!fallbackExcludesPrimary(v.channel, v.fallbackChannels)) e.fallback = 'Fallback channels cannot include the primary channel.';
  if (v.channel === 'EMAIL' && !isEmail(v.replyTo)) e.replyTo = 'Enter a valid e-mail address';
  if (v.channel === 'SMS' && !isSmsSenderId(v.senderId)) e.senderId = 'Sender id must be 3-11 letters/digits';
  if (v.channel === 'PUSH' && !isIntegerInRange(v.ttlSeconds, 1, MAX_PUSH_TTL_SECONDS)) e.ttlSeconds = `TTL must be 1 to ${MAX_PUSH_TTL_SECONDS}`;
  if (!v.templateCode.trim()) e.templateCode = 'Template code is required';
  if (!v.locale.trim() || !isLocale(v.locale)) e.locale = 'Use a locale like en or en-US';
  if (!isIntegerInRange(v.templateVersion, 1, 100000)) e.templateVersion = 'Version must be a whole number from 1 to 100000';
  if (v.variables.some((r) => !isVariableKey(r.key))) e.variables = 'Letters, digits, underscore; start with a letter';
  if (!hasUniqueKeys(v.variables)) e.variableKeys = 'Variable keys must be unique.';
  if (futureDateError(v.sendAt, now)) e.sendAt = 'Send time must be in the future';
  if (!expiryAfterSend(v.sendAt, v.expiresAt)) e.expiry = 'Expiry must be after the send time.';
  return e;
}
