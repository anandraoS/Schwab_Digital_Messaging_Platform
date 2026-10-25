import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AuditEvent, PageResponse, TemplateCreateRequest, TemplateDetail, TemplatePreviewRequest,
  TemplatePreviewResponse, TemplateSummary, TemplateVersionRequest,
} from '../models/api.models';
import { API, toParams } from './api-utils';

export type TemplateAction = 'submit' | 'approve' | 'reject' | 'retire';

@Injectable({ providedIn: 'root' })
export class TemplatesService {
  private readonly http = inject(HttpClient);

  list(filter: { channel?: string; status?: string; code?: string; page?: number; size?: number }):
    Observable<PageResponse<TemplateSummary>> {
    return this.http.get<PageResponse<TemplateSummary>>(`${API}/templates`, { params: toParams(filter) });
  }

  get(id: string): Observable<TemplateDetail> {
    return this.http.get<TemplateDetail>(`${API}/templates/${encodeURIComponent(id)}`);
  }

  create(body: TemplateCreateRequest): Observable<TemplateDetail> {
    return this.http.post<TemplateDetail>(`${API}/templates`, body);
  }

  addVersion(id: string, body: TemplateVersionRequest): Observable<unknown> {
    return this.http.post(`${API}/templates/${encodeURIComponent(id)}/versions`, body);
  }

  updateVersion(id: string, versionNo: number, locale: string, body: TemplateVersionRequest): Observable<unknown> {
    return this.http.put(`${API}/templates/${encodeURIComponent(id)}/versions/${versionNo}`, body, {
      params: toParams({ locale }),
    });
  }

  /** submit / approve / reject / retire a version. `comment` is sent for reject. */
  act(id: string, versionNo: number, locale: string, action: TemplateAction, comment?: string): Observable<unknown> {
    return this.http.post(
      `${API}/templates/${encodeURIComponent(id)}/versions/${versionNo}/${action}`,
      comment ? { comment } : {},
      { params: toParams({ locale }) },
    );
  }

  preview(body: TemplatePreviewRequest): Observable<TemplatePreviewResponse> {
    return this.http.post<TemplatePreviewResponse>(`${API}/templates/preview`, body);
  }

  history(id: string): Observable<AuditEvent[]> {
    return this.http.get<AuditEvent[]>(`${API}/templates/${encodeURIComponent(id)}/history`);
  }
}
