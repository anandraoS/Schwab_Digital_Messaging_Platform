import { API, apiRequestRaw, get, post } from '../http/client';
import type {
  HistoryEntry, MessageAcceptedResponse, MessageDetail, MessageFilter, MessageSummary, PageResponse,
  SubmitMessageRequest, SubmitResult,
} from '../models';

const enc = encodeURIComponent;

export const messagesApi = {
  list: (filter: MessageFilter) => get<PageResponse<MessageSummary>>(`${API}/messages`, filter),
  get: (id: string) => get<MessageDetail>(`${API}/messages/${enc(id)}`),
  history: (id: string) => get<HistoryEntry[]>(`${API}/messages/${enc(id)}/history`),
  /** Sends the Idempotency-Key header; 202 = accepted, 200 + Idempotent-Replay = replay of an earlier request. */
  async submit(body: SubmitMessageRequest, idempotencyKey: string): Promise<SubmitResult> {
    const res = await apiRequestRaw<MessageAcceptedResponse>('POST', `${API}/messages`, {
      body, headers: { 'Idempotency-Key': idempotencyKey },
    });
    return { httpStatus: res.status, replay: res.headers.get('Idempotent-Replay') === 'true', body: res.body };
  },
  cancel: (id: string) => post<unknown>(`${API}/messages/${enc(id)}/cancel`),
  requeue: (id: string) => post<unknown>(`${API}/messages/${enc(id)}/requeue`),
};
