import { API, get, post } from '../http/client';
import type {
  BulkJob, BulkJobFailure, BulkUploadParams, PageParams, PageResponse, SyntheticJobRequest,
} from '../models';

const enc = encodeURIComponent;

export const bulkJobsApi = {
  list: (page: PageParams = {}) => get<PageResponse<BulkJob>>(`${API}/bulk-jobs`, page),
  /** `quiet` suppresses the global loading bar (used by progress polling). */
  get: (id: string, quiet = false) => get<BulkJob>(`${API}/bulk-jobs/${enc(id)}`, undefined, { quiet }),
  failures: (id: string, page: PageParams = {}) => get<PageResponse<BulkJobFailure>>(`${API}/bulk-jobs/${enc(id)}/failures`, page),
  upload(file: File, params: BulkUploadParams): Promise<BulkJob> {
    const form = new FormData();
    form.append('file', file, file.name);
    for (const [k, v] of Object.entries(params)) {
      form.append(k, String(v));
    }
    return post<BulkJob>(`${API}/bulk-jobs`, form);
  },
  generateSynthetic: (req: SyntheticJobRequest) => post<BulkJob>(`${API}/bulk-jobs/generate-synthetic`, req),
  stop: (id: string) => post<unknown>(`${API}/bulk-jobs/${enc(id)}/stop`),
  restart: (id: string) => post<unknown>(`${API}/bulk-jobs/${enc(id)}/restart`),
};
