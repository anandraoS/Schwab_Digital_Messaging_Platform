import { API, get, post, put } from '../http/client';
import type {
  AuditEvent, PageResponse, TemplateCreateRequest, TemplateDetail, TemplatePreviewRequest, TemplatePreviewResponse,
  TemplateSummary, TemplateVersionRequest,
} from '../models';

const enc = encodeURIComponent;

export type TemplateAction = 'submit' | 'approve' | 'reject' | 'retire';

export const templatesApi = {
  list: (filter: { channel?: string; status?: string; code?: string; page?: number; size?: number }) =>
    get<PageResponse<TemplateSummary>>(`${API}/templates`, filter),
  get: (id: string) => get<TemplateDetail>(`${API}/templates/${enc(id)}`),
  create: (body: TemplateCreateRequest) => post<TemplateDetail>(`${API}/templates`, body),
  addVersion: (id: string, body: TemplateVersionRequest) => post<unknown>(`${API}/templates/${enc(id)}/versions`, body),
  updateVersion: (id: string, versionNo: number, locale: string, body: TemplateVersionRequest) =>
    put<unknown>(`${API}/templates/${enc(id)}/versions/${versionNo}?locale=${enc(locale)}`, body),
  /** submit / approve / reject / retire a version. `comment` is sent for reject. */
  act: (id: string, versionNo: number, locale: string, action: TemplateAction, comment?: string) =>
    post<unknown>(`${API}/templates/${enc(id)}/versions/${versionNo}/${action}?locale=${enc(locale)}`, comment ? { comment } : {}),
  preview: (body: TemplatePreviewRequest) => post<TemplatePreviewResponse>(`${API}/templates/preview`, body),
  history: (id: string) => get<AuditEvent[]>(`${API}/templates/${enc(id)}/history`),
};
