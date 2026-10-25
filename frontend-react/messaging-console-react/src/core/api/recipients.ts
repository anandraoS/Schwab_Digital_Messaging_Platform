import { API, get, put } from '../http/client';
import type { PageResponse, RecipientPreferences, RecipientSummary, UpdatePreferencesRequest } from '../models';

const enc = encodeURIComponent;

export const recipientsApi = {
  search: (q: string, page = 0, size = 10) => get<PageResponse<RecipientSummary>>(`${API}/recipients`, { q, page, size }),
  getPreferences: (id: string) => get<RecipientPreferences>(`${API}/recipients/${enc(id)}/preferences`),
  /** `prefs.version` must be the version loaded; a stale value yields 409 CONFLICT_OPTIMISTIC_LOCK. */
  updatePreferences: (id: string, prefs: UpdatePreferencesRequest) =>
    put<RecipientPreferences>(`${API}/recipients/${enc(id)}/preferences`, prefs),
};
