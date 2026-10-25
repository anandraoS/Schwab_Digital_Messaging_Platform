/**
 * Typed API models, ported 1:1 from the Angular console (core/models/api.models.ts) which was reconciled against the
 * real backend (messaging-api-v1.yaml). Keep both in sync: same backend, same contract.
 */

export type Channel = 'EMAIL' | 'SMS' | 'PUSH';
export type Priority = 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW';
export type Category = 'TRANSACTIONAL' | 'SECURITY_ALERT' | 'SERVICE' | 'MARKETING';
export type MessageStatus =
  | 'ACCEPTED' | 'VALIDATED' | 'QUEUED' | 'PROCESSING' | 'SENT_TO_PROVIDER' | 'DELIVERED'
  | 'REJECTED' | 'RETRY_PENDING' | 'FAILED' | 'DEAD_LETTERED' | 'CANCELLED' | 'DELIVERY_UNKNOWN';
export type TemplateStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'PUBLISHED' | 'RETIRED';
export type DeliveryEventType =
  | 'PROVIDER_ACCEPTED' | 'SENT' | 'DELIVERED' | 'BOUNCED' | 'FAILED' | 'REJECTED'
  | 'DEFERRED' | 'OPENED' | 'CLICKED' | 'UNSUBSCRIBED';
export type ProviderOutcome =
  | 'ACCEPTED' | 'TRANSIENT_FAILURE' | 'PERMANENT_FAILURE' | 'RATE_LIMITED' | 'TIMEOUT' | 'AMBIGUOUS';
export type BulkJobStatus =
  | 'CREATED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED' | 'STOPPED' | 'ABANDONED';
export type DlqReason = 'RETRIES_EXHAUSTED' | 'POISON_MESSAGE' | 'PERMANENT_FAILURE' | 'OPERATOR';
export type FaultMode =
  | 'NONE' | 'TRANSIENT' | 'PERMANENT' | 'RATE_LIMIT' | 'TIMEOUT' | 'AMBIGUOUS' | 'ALTERNATING';
export type Role =
  | 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'TEMPLATE_AUTHOR' | 'TEMPLATE_APPROVER'
  | 'OPERATOR' | 'SENDER' | 'AUDITOR';

export const CHANNELS: Channel[] = ['EMAIL', 'SMS', 'PUSH'];
export const PRIORITIES: Priority[] = ['CRITICAL', 'HIGH', 'NORMAL', 'LOW'];
export const CATEGORIES: Category[] = ['TRANSACTIONAL', 'SECURITY_ALERT', 'SERVICE', 'MARKETING'];
export const MESSAGE_STATUSES: MessageStatus[] = [
  'ACCEPTED', 'VALIDATED', 'QUEUED', 'PROCESSING', 'SENT_TO_PROVIDER', 'DELIVERED',
  'REJECTED', 'RETRY_PENDING', 'FAILED', 'DEAD_LETTERED', 'CANCELLED', 'DELIVERY_UNKNOWN',
];
export const TERMINAL_STATUSES: MessageStatus[] = ['DELIVERED', 'REJECTED', 'CANCELLED'];
export const FAULT_MODES: FaultMode[] = [
  'NONE', 'TRANSIENT', 'PERMANENT', 'RATE_LIMIT', 'TIMEOUT', 'AMBIGUOUS', 'ALTERNATING',
];
export const DELIVERY_EVENT_TYPES: DeliveryEventType[] = [
  'PROVIDER_ACCEPTED', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED', 'REJECTED',
  'DEFERRED', 'OPENED', 'CLICKED', 'UNSUBSCRIBED',
];

export interface ApiErrorDetail {
  field: string;
  message: string;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  correlationId: string;
  details?: ApiErrorDetail[];
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort?: string;
}

export interface PageParams {
  page?: number;
  size?: number;
  sort?: string;
}

// ---- auth -------------------------------------------------------------------------------------

export interface DevTokenRequest {
  subject: string;
  roles: string[];
  tenant: string;
  /** Optional OAuth scopes, e.g. `messages:bulk` (required for bulk send). */
  scopes?: string[];
}

export interface DevTokenResponse {
  access_token: string;
  expires_in: number;
}

export interface Persona {
  id: string;
  label: string;
  description: string;
  request: DevTokenRequest;
}

export interface Session {
  token: string;
  subject: string;
  tenant: string;
  roles: Role[];
  expiresAt: number; // epoch ms
}

export interface MeResponse {
  subject?: string;
  sub?: string;
  roles?: string[];
  tenant?: string;
}

// ---- messages ---------------------------------------------------------------------------------

export interface SubmitMessageRequest {
  clientReference?: string;
  recipientId?: string;
  recipient?: { externalRef: string };
  channel: Channel;
  category: Category;
  priority: Priority;
  template: { code: string; locale: string; version?: number | null };
  variables?: Record<string, string>;
  channelOptions?: {
    email?: { replyTo?: string; headers?: Record<string, string> };
    sms?: { senderId?: string };
    push?: { collapseKey?: string; ttlSeconds?: number };
  };
  fallbackChannels?: Channel[];
  schedule?: { sendAt?: string; expiresAt?: string };
  metadata?: Record<string, string>;
}

export interface MessageAcceptedResponse {
  id: string;
  status: MessageStatus;
  channel: Channel;
  correlationId: string;
  scheduledAt?: string | null;
  links?: Record<string, string>;
}

/** Wrapper so the UI can tell a 200 idempotent replay from a fresh 202. */
export interface SubmitResult {
  httpStatus: number;
  replay: boolean;
  body: MessageAcceptedResponse;
}

export interface MessageSummary {
  id: string;
  clientReference?: string;
  channel: Channel;
  channelUsed?: Channel | null;
  category: Category;
  priority: Priority;
  status: MessageStatus;
  statusReason?: string | null;
  templateCode?: string;
  recipientId?: string;
  correlationId?: string;
  nextAttemptAt?: string | null;
  createdAt: string;
  updatedAt?: string;
}

export interface MessageAttempt {
  attemptNo: number;
  channel: Channel;
  providerId: string;
  outcome: ProviderOutcome | string;
  providerMessageId?: string | null;
  errorCode?: string | null;
  latencyMs?: number | null;
  startedAt: string;
}

export interface DeliveryEvent {
  id?: string;
  messageId?: string;
  providerId: string;
  providerEventId?: string;
  type: DeliveryEventType | string;
  occurredAt: string;
  receivedAt?: string;
  applied: boolean;
  ignoredReason?: string | null;
}

export interface MessageDetail {
  id: string;
  tenantId: string;
  clientReference?: string;
  recipient: { id: string; externalRef?: string; maskedAddress?: string };
  channel: Channel;
  channelUsed?: Channel | null;
  category: Category;
  priority: Priority;
  status: MessageStatus;
  statusReason?: string | null;
  terminal: boolean;
  template: { code: string; versionNo?: number; locale?: string };
  correlationId: string;
  idempotencyKey?: string;
  scheduledAt?: string | null;
  expiresAt?: string | null;
  attemptCount: number;
  maxAttempts: number;
  nextAttemptAt?: string | null;
  createdAt: string;
  updatedAt: string;
  attempts: MessageAttempt[];
  deliveryEvents: DeliveryEvent[];
}

export interface MessageFilter extends PageParams {
  status?: string;
  channel?: string;
  category?: string;
  priority?: string;
  recipientId?: string;
  correlationId?: string;
  from?: string;
  to?: string;
  q?: string;
}

/** Backend `TimelineEntry`: merged audit events, attempts and provider receipts. The actor of an audit event is
 *  `detail.actor`, the target status of a transition `detail.to`. */
export interface HistoryEntry {
  at: string;
  kind: 'AUDIT' | 'ATTEMPT' | 'DELIVERY_EVENT' | string;
  type: string;
  summary: string;
  detail?: Record<string, unknown> | null;
}

// ---- dead letters / failed / events / audit ---------------------------------------------------

export interface DeadLetter {
  messageId: string;
  channel: Channel;
  reason: DlqReason | string;
  deadLetteredAt: string;
  attemptCount?: number;
  discarded?: boolean;
  tenantId?: string;
  lastError?: string | null;
}

export interface FailedMessage {
  id: string;
  status: MessageStatus;
  channel: Channel;
  statusReason?: string | null;
  attemptCount: number;
  nextAttemptAt?: string | null;
  updatedAt: string;
}

export interface AuditEvent {
  id: string;
  tenantId?: string;
  actor: string;
  actorRole?: string;
  action: string;
  entityType: string;
  entityId: string;
  correlationId?: string;
  detail?: string | Record<string, unknown> | null;
  occurredAt: string;
}

export interface DeliveryEventFilter extends PageParams {
  messageId?: string;
  providerId?: string;
  type?: string;
  from?: string;
  to?: string;
  applied?: string;
}

export interface AuditFilter extends PageParams {
  entityType?: string;
  entityId?: string;
  action?: string;
  actor?: string;
  from?: string;
  to?: string;
}

// ---- templates --------------------------------------------------------------------------------

export interface TemplateVersion {
  id?: string;
  versionNo: number;
  locale: string;
  status: TemplateStatus;
  subject?: string | null;
  body: string;
  variables: string[];
  createdBy?: string;
  approvedBy?: string | null;
  publishedAt?: string | null;
  version?: number; // optimistic-lock counter
  /** Reviewer comment of the last rejection (backend `rejectionComment`). */
  rejectionComment?: string | null;
}

export interface TemplateSummary {
  id: string;
  tenantId?: string;
  code: string;
  name?: string;
  channel: Channel;
  category: Category;
  latestVersionNo?: number;
  /** Locales that have a PUBLISHED version. */
  publishedLocales?: string[];
  /** Number of versions per status, e.g. {PUBLISHED: 1, DRAFT: 1}. */
  versionCounts?: Partial<Record<TemplateStatus, number>>;
  updatedAt?: string;
}

export interface TemplateDetail extends TemplateSummary {
  versions: TemplateVersion[];
}

export interface TemplateCreateRequest {
  code: string;
  /** Human readable name (required by the backend). */
  name: string;
  channel: Channel;
  category: Category;
  locale: string;
  subject?: string;
  body: string;
  variables: string[];
}

export interface TemplateVersionRequest {
  locale: string;
  subject?: string;
  body: string;
  variables: string[];
  version?: number;
}

export interface TemplatePreviewRequest {
  channel: Channel;
  subject?: string;
  body: string;
  variables: string[];
  sampleData: Record<string, string>;
  locale: string;
}

export interface TemplatePreviewResponse {
  subject?: string | null;
  body?: string | null;
  valid?: boolean;
  errors?: ApiErrorDetail[];
}

// ---- recipients / preferences -----------------------------------------------------------------

export interface RecipientSummary {
  id: string;
  tenantId?: string;
  externalRef: string;
  displayName?: string;
  maskedEmail?: string | null;
  maskedPhone?: string | null;
  locale?: string;
  timeZone?: string;
  deleted?: boolean;
}

/** Backend `PreferencesResponse`. `quietStart`/`quietEnd` are null when the default window (21:00-08:00) applies. */
export interface RecipientPreferences {
  recipientId: string;
  marketingOptIn: boolean;
  emailEnabled: boolean;
  smsEnabled: boolean;
  pushEnabled: boolean;
  quietStart: string | null;
  quietEnd: string | null;
  timeZone?: string | null;
  fallbackOrder: Channel[];
  suppressedChannels?: Channel[];
  /** null only for a recipient without a stored preference row (defaults shown). */
  version: number | null;
  updatedAt?: string | null;
}

/** Backend `UpdatePreferencesRequest`. */
export interface UpdatePreferencesRequest {
  marketingOptIn: boolean;
  emailEnabled: boolean;
  smsEnabled: boolean;
  pushEnabled: boolean;
  quietStart: string | null;
  quietEnd: string | null;
  timeZone?: string | null;
  fallbackOrder: Channel[];
  version: number | null;
}

// ---- bulk jobs --------------------------------------------------------------------------------

export interface BulkJob {
  id: string;
  name: string;
  status: BulkJobStatus;
  total: number;
  processed: number;
  succeeded: number;
  failed: number;
  duplicate: number;
  fileName?: string | null;
  ratePerSecond?: number;
  startedAt?: string | null;
  finishedAt?: string | null;
  createdBy?: string;
}

export interface BulkJobFailure {
  lineNo: number;
  reason: string;
  rawLineHash?: string;
}

export interface BulkUploadParams {
  name: string;
  templateCode: string;
  channel: Channel;
  category: Category;
  ratePerSecond: number;
}

export interface SyntheticJobRequest {
  rows: number;
  duplicateRate: number;
  invalidRate: number;
}

// ---- providers / ops --------------------------------------------------------------------------

export interface ProviderHealth {
  providerId: string;
  channel: Channel;
  enabled: boolean;
  circuitState: 'CLOSED' | 'HALF_OPEN' | 'OPEN' | string;
  successRate: number; // 0..1
  p95LatencyMs: number;
  rateLimitPerSec: number;
  timeoutMs?: number;
  lastError?: string | null;
  faultMode?: FaultMode | string;
  faultProbability?: number;
}

export interface ProviderConfigRequest {
  enabled: boolean;
  rateLimitPerSec: number;
  timeoutMs: number;
}

export interface FaultModeRequest {
  mode: FaultMode;
  probability: number;
}

export interface QueueStats {
  depthByPriority: Record<string, number>;
  inFlight: number;
  consumers: number;
  consumersPaused?: boolean;
  dlqDepth: number;
  retryPending: number;
  oldestMessageAgeSeconds: number;
}

export interface OpsSummary {
  windowHours?: number;
  total: number;
  byStatus: Record<string, number>;
  byChannel: Record<string, number>;
  successRate: number; // 0..1
  p95DispatchDelayMs?: number;
}
