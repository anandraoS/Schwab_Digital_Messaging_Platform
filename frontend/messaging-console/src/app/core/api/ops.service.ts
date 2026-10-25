import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  FaultModeRequest, OpsSummary, ProviderConfigRequest, ProviderHealth, QueueStats,
} from '../models/api.models';
import { SKIP_LOADING } from '../http/interceptors';
import { API } from './api-utils';

@Injectable({ providedIn: 'root' })
export class OpsService {
  private readonly http = inject(HttpClient);

  summary(): Observable<OpsSummary> {
    return this.http.get<OpsSummary>(`${API}/ops/summary`);
  }

  queues(quiet = false): Observable<QueueStats> {
    return this.http.get<QueueStats>(`${API}/ops/queues`, { context: new HttpContext().set(SKIP_LOADING, quiet) });
  }

  providers(): Observable<ProviderHealth[]> {
    return this.http.get<ProviderHealth[]>(`${API}/providers`);
  }

  updateProviderConfig(providerId: string, body: ProviderConfigRequest): Observable<unknown> {
    return this.http.put(`${API}/providers/${encodeURIComponent(providerId)}/config`, body);
  }

  setFaultMode(providerId: string, body: FaultModeRequest): Observable<unknown> {
    return this.http.put(`${API}/providers/${encodeURIComponent(providerId)}/fault-mode`, body);
  }

  pauseConsumers(): Observable<unknown> {
    return this.http.post(`${API}/ops/consumers/pause`, {});
  }

  resumeConsumers(): Observable<unknown> {
    return this.http.post(`${API}/ops/consumers/resume`, {});
  }
}
