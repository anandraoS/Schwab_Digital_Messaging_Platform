import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import {
  HistoryEntry, MessageAcceptedResponse, MessageDetail, MessageFilter, MessageSummary, PageResponse,
  SubmitMessageRequest, SubmitResult,
} from '../models/api.models';
import { API, toParams } from './api-utils';

@Injectable({ providedIn: 'root' })
export class MessagesService {
  private readonly http = inject(HttpClient);

  list(filter: MessageFilter): Observable<PageResponse<MessageSummary>> {
    return this.http.get<PageResponse<MessageSummary>>(`${API}/messages`, { params: toParams(filter) });
  }

  get(id: string): Observable<MessageDetail> {
    return this.http.get<MessageDetail>(`${API}/messages/${encodeURIComponent(id)}`);
  }

  history(id: string): Observable<HistoryEntry[]> {
    return this.http.get<HistoryEntry[]>(`${API}/messages/${encodeURIComponent(id)}/history`);
  }

  /** Sends the Idempotency-Key header; 202 = accepted, 200 + Idempotent-Replay = replay of an earlier request. */
  submit(body: SubmitMessageRequest, idempotencyKey: string): Observable<SubmitResult> {
    return this.http
      .post<MessageAcceptedResponse>(`${API}/messages`, body, {
        headers: { 'Idempotency-Key': idempotencyKey },
        observe: 'response',
      })
      .pipe(map((res: HttpResponse<MessageAcceptedResponse>) => ({
        httpStatus: res.status,
        replay: res.headers.get('Idempotent-Replay') === 'true',
        body: res.body as MessageAcceptedResponse,
      })));
  }

  cancel(id: string): Observable<unknown> {
    return this.http.post(`${API}/messages/${encodeURIComponent(id)}/cancel`, {});
  }

  requeue(id: string): Observable<unknown> {
    return this.http.post(`${API}/messages/${encodeURIComponent(id)}/requeue`, {});
  }
}
