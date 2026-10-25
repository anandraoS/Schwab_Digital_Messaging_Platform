import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AuditEvent, AuditFilter, DeadLetter, DeliveryEvent, DeliveryEventFilter, FailedMessage, PageParams,
  PageResponse,
} from '../models/api.models';
import { API, toParams } from './api-utils';

/** Operator investigation endpoints: dead letters, failed messages, delivery events, audit. */
@Injectable({ providedIn: 'root' })
export class EventsService {
  private readonly http = inject(HttpClient);

  deadLetters(page: PageParams): Observable<PageResponse<DeadLetter>> {
    return this.http.get<PageResponse<DeadLetter>>(`${API}/dead-letters`, { params: toParams(page) });
  }

  replay(messageId: string): Observable<unknown> {
    return this.http.post(`${API}/dead-letters/${encodeURIComponent(messageId)}/replay`, {});
  }

  discard(messageId: string): Observable<unknown> {
    return this.http.post(`${API}/dead-letters/${encodeURIComponent(messageId)}/discard`, {});
  }

  failed(page: PageParams): Observable<PageResponse<FailedMessage>> {
    return this.http.get<PageResponse<FailedMessage>>(`${API}/failed-messages`, { params: toParams(page) });
  }

  deliveryEvents(filter: DeliveryEventFilter): Observable<PageResponse<DeliveryEvent>> {
    return this.http.get<PageResponse<DeliveryEvent>>(`${API}/delivery-events`, { params: toParams(filter) });
  }

  audit(filter: AuditFilter): Observable<PageResponse<AuditEvent>> {
    return this.http.get<PageResponse<AuditEvent>>(`${API}/audit-events`, { params: toParams(filter) });
  }
}
