import { API, get, post, put } from '../http/client';
import type { FaultModeRequest, OpsSummary, ProviderConfigRequest, ProviderHealth, QueueStats } from '../models';

const enc = encodeURIComponent;

export const opsApi = {
  summary: () => get<OpsSummary>(`${API}/ops/summary`),
  queues: (quiet = false) => get<QueueStats>(`${API}/ops/queues`, undefined, { quiet }),
  providers: () => get<ProviderHealth[]>(`${API}/providers`),
  updateProviderConfig: (providerId: string, body: ProviderConfigRequest) =>
    put<unknown>(`${API}/providers/${enc(providerId)}/config`, body),
  setFaultMode: (providerId: string, body: FaultModeRequest) =>
    put<unknown>(`${API}/providers/${enc(providerId)}/fault-mode`, body),
  pauseConsumers: () => post<unknown>(`${API}/ops/consumers/pause`),
  resumeConsumers: () => post<unknown>(`${API}/ops/consumers/resume`),
};
