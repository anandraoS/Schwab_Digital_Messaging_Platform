import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BulkJob, BulkJobFailure, BulkUploadParams, PageParams, PageResponse, SyntheticJobRequest,
} from '../models/api.models';
import { SKIP_LOADING } from '../http/interceptors';
import { API, toParams } from './api-utils';

@Injectable({ providedIn: 'root' })
export class BulkJobsService {
  private readonly http = inject(HttpClient);

  list(page: PageParams = {}): Observable<PageResponse<BulkJob>> {
    return this.http.get<PageResponse<BulkJob>>(`${API}/bulk-jobs`, { params: toParams(page) });
  }

  /** `quiet` suppresses the global loading bar (used by progress polling). */
  get(id: string, quiet = false): Observable<BulkJob> {
    return this.http.get<BulkJob>(`${API}/bulk-jobs/${encodeURIComponent(id)}`, {
      context: new HttpContext().set(SKIP_LOADING, quiet),
    });
  }

  failures(id: string, page: PageParams = {}): Observable<PageResponse<BulkJobFailure>> {
    return this.http.get<PageResponse<BulkJobFailure>>(`${API}/bulk-jobs/${encodeURIComponent(id)}/failures`, {
      params: toParams(page),
    });
  }

  upload(file: File, params: BulkUploadParams): Observable<BulkJob> {
    const form = new FormData();
    form.append('file', file, file.name);
    for (const [k, v] of Object.entries(params)) {
      form.append(k, String(v));
    }
    return this.http.post<BulkJob>(`${API}/bulk-jobs`, form);
  }

  generateSynthetic(req: SyntheticJobRequest): Observable<BulkJob> {
    return this.http.post<BulkJob>(`${API}/bulk-jobs/generate-synthetic`, req);
  }

  stop(id: string): Observable<unknown> {
    return this.http.post(`${API}/bulk-jobs/${encodeURIComponent(id)}/stop`, {});
  }

  restart(id: string): Observable<unknown> {
    return this.http.post(`${API}/bulk-jobs/${encodeURIComponent(id)}/restart`, {});
  }
}
