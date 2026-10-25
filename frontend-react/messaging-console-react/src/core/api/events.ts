import { API, get, post } from '../http/client';
import type {
  AuditEvent, AuditFilter, DeadLetter, DeliveryEvent, DeliveryEventFilter, FailedMessage, PageParams, PageResponse,
} from '../models';

const enc = encodeURIComponent;

/** Operator investigation endpoints: dead letters, failed messages, delivery events, audit. */
export const eventsApi = {
  deadLetters: (page: PageParams) => get<PageResponse<DeadLetter>>(`${API}/dead-letters`, page),
  replay: (messageId: string) => post<unknown>(`${API}/dead-letters/${enc(messageId)}/replay`),
  discard: (messageId: string) => post<unknown>(`${API}/dead-letters/${enc(messageId)}/discard`),
  failed: (page: PageParams) => get<PageResponse<FailedMessage>>(`${API}/failed-messages`, page),
  deliveryEvents: (filter: DeliveryEventFilter) => get<PageResponse<DeliveryEvent>>(`${API}/delivery-events`, filter),
  audit: (filter: AuditFilter) => get<PageResponse<AuditEvent>>(`${API}/audit-events`, filter),
};
