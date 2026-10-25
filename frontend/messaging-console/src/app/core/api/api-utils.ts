import { HttpParams } from '@angular/common/http';

export const API = '/api/v1';

/** Builds HttpParams, dropping null/undefined/empty-string values. */
export function toParams(obj: object): HttpParams {
  let params = new HttpParams();
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    if (v === null || v === undefined || v === '') {
      continue;
    }
    params = params.set(k, String(v));
  }
  return params;
}
