import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PageResponse, RecipientPreferences, RecipientSummary, UpdatePreferencesRequest } from '../models/api.models';
import { API, toParams } from './api-utils';

@Injectable({ providedIn: 'root' })
export class RecipientsService {
  private readonly http = inject(HttpClient);

  search(q: string, page = 0, size = 10): Observable<PageResponse<RecipientSummary>> {
    return this.http.get<PageResponse<RecipientSummary>>(`${API}/recipients`, { params: toParams({ q, page, size }) });
  }

  getPreferences(id: string): Observable<RecipientPreferences> {
    return this.http.get<RecipientPreferences>(`${API}/recipients/${encodeURIComponent(id)}/preferences`);
  }

  /** `prefs.version` must be the version loaded; a stale value yields 409 CONFLICT_OPTIMISTIC_LOCK. */
  updatePreferences(id: string, prefs: UpdatePreferencesRequest): Observable<RecipientPreferences> {
    return this.http.put<RecipientPreferences>(`${API}/recipients/${encodeURIComponent(id)}/preferences`, prefs);
  }
}
