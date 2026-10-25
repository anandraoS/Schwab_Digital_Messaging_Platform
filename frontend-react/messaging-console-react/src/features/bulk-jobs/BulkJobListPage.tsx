import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { DataTable, type Column } from '../../components/DataTable';
import { ErrorBanner } from '../../components/ErrorBanner';
import { SelectField, TextField } from '../../components/Fields';
import { errOf, useAction, usePaging } from '../../components/hooks';
import { bulkJobsApi } from '../../core/api/bulkJobs';
import { isIntegerInRange, isNumberInRange, isTemplateCode, validateCsvFile } from '../../core/forms/validators';
import { CATEGORIES, CHANNELS, type BulkJob, type Category, type Channel } from '../../core/models';

const columns: Column<BulkJob>[] = [
  { key: 'name', header: 'Name', link: (r) => `/bulk-jobs/${encodeURIComponent(r.id)}` },
  { key: 'status', header: 'Status', type: 'status' },
  { key: 'processed', header: 'Processed', value: (r) => `${r.processed} / ${r.total}` },
  { key: 'failed', header: 'Failed' },
  { key: 'duplicate', header: 'Duplicates' },
  { key: 'startedAt', header: 'Started', type: 'date' },
];

export function BulkJobListPage() {
  const navigate = useNavigate();
  const paging = usePaging();
  const run = useAction();
  const list = useQuery({
    queryKey: ['bulk-jobs', paging.page, paging.size],
    queryFn: () => bulkJobsApi.list({ page: paging.page, size: paging.size, sort: 'startedAt,desc' }),
  });

  const [up, setUp] = useState({ name: '', templateCode: '', channel: 'EMAIL' as Channel, category: 'MARKETING' as Category, ratePerSecond: '20' });
  const [upTouched, setUpTouched] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState('');
  const [synth, setSynth] = useState({ rows: '1000', duplicateRate: '0.02', invalidRate: '0.02' });
  const [synthTouched, setSynthTouched] = useState(false);

  const upErr = {
    name: !up.name.trim() ? 'Job name is required' : undefined,
    templateCode: !up.templateCode || !isTemplateCode(up.templateCode) ? 'Upper-case code, e.g. TRADE_CONFIRMATION' : undefined,
    ratePerSecond: !up.ratePerSecond || !isIntegerInRange(up.ratePerSecond, 1, 1000) ? '1 to 1000' : undefined,
  };
  const synthErr = {
    rows: !synth.rows || !isIntegerInRange(synth.rows, 1, 1_000_000) ? '1 to 1,000,000' : undefined,
    duplicateRate: !synth.duplicateRate || !isNumberInRange(synth.duplicateRate, 0, 1) ? '0 to 1' : undefined,
    invalidRate: !synth.invalidRate || !isNumberInRange(synth.invalidRate, 0, 1) ? '0 to 1' : undefined,
  };

  const launch = async (call: () => Promise<BulkJob>): Promise<void> => {
    let job: BulkJob | null = null;
    if (await run.run(async () => { job = await call(); })) {
      navigate(`/bulk-jobs/${encodeURIComponent((job as unknown as BulkJob).id)}`);
    }
  };

  const upload = (e: FormEvent): void => {
    e.preventDefault();
    setUpTouched(true);
    const err = validateCsvFile(file);
    setFileError(err ?? '');
    if (err || !file || Object.values(upErr).some(Boolean)) return;
    void launch(() => bulkJobsApi.upload(file, {
      name: up.name, templateCode: up.templateCode, channel: up.channel, category: up.category, ratePerSecond: Number(up.ratePerSecond),
    }));
  };

  const generate = (e: FormEvent): void => {
    e.preventDefault();
    setSynthTouched(true);
    if (Object.values(synthErr).some(Boolean)) return;
    void launch(() => bulkJobsApi.generateSynthetic({
      rows: Number(synth.rows), duplicateRate: Number(synth.duplicateRate), invalidRate: Number(synth.invalidRate),
    }));
  };

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Bulk jobs</h1>
        <button type="button" className="btn" onClick={() => void list.refetch()}>Refresh</button>
      </div>
      <ErrorBanner error={run.error} retryable={false} />
      <div className="grid forms" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', marginBottom: 16 }}>
        <section className="card">
          <h2>Upload CSV</h2>
          <p className="muted">Header: <code>externalRef,locale,var_firstName,var_amount,…</code>. Max 10 MB here; the server enforces its own row limit.</p>
          <form className="stack" onSubmit={upload} noValidate aria-label="Upload bulk job">
            <TextField label="Job name" name="name" value={up.name} onChange={(e) => setUp({ ...up, name: e.target.value })} error={upTouched ? upErr.name : undefined} />
            <TextField label="Template code" name="templateCode" value={up.templateCode} onChange={(e) => setUp({ ...up, templateCode: e.target.value })} error={upTouched ? upErr.templateCode : undefined} />
            <SelectField label="Channel" name="channel" value={up.channel} onChange={(e) => setUp({ ...up, channel: e.target.value as Channel })} options={CHANNELS} />
            <SelectField label="Category" name="category" value={up.category} onChange={(e) => setUp({ ...up, category: e.target.value as Category })} options={CATEGORIES} />
            <TextField label="Rate per second" name="ratePerSecond" type="number" value={up.ratePerSecond} onChange={(e) => setUp({ ...up, ratePerSecond: e.target.value })} error={upTouched ? upErr.ratePerSecond : undefined} />
            <div className="field">
              <label htmlFor="csv">CSV file</label>
              <input id="csv" name="file" type="file" accept=".csv,text/csv" onChange={(e) => {
                const f = e.target.files?.[0] ?? null;
                setFile(f);
                setFileError(validateCsvFile(f) ?? '');
              }} />
              {fileError ? <div className="field-err" role="alert">{fileError}</div> : null}
            </div>
            <button type="submit" className="btn primary" disabled={run.busy}>Upload and start</button>
          </form>
        </section>
        <section className="card">
          <h2>Generate synthetic job</h2>
          <p className="muted">Creates and submits a synthetic data set server-side. No upload.</p>
          <form className="stack" onSubmit={generate} noValidate aria-label="Generate synthetic job">
            <TextField label="Rows" name="rows" type="number" value={synth.rows} onChange={(e) => setSynth({ ...synth, rows: e.target.value })} error={synthTouched ? synthErr.rows : undefined} />
            <TextField label="Duplicate rate (0-1)" name="duplicateRate" type="number" step="0.01" value={synth.duplicateRate} onChange={(e) => setSynth({ ...synth, duplicateRate: e.target.value })} error={synthTouched ? synthErr.duplicateRate : undefined} />
            <TextField label="Invalid rate (0-1)" name="invalidRate" type="number" step="0.01" value={synth.invalidRate} onChange={(e) => setSynth({ ...synth, invalidRate: e.target.value })} error={synthTouched ? synthErr.invalidRate : undefined} />
            <button type="submit" className="btn primary" disabled={run.busy}>Generate</button>
          </form>
        </section>
      </div>
      <h2>Jobs</h2>
      <DataTable
        caption="Bulk jobs" columns={columns} rows={list.data?.content ?? []} loading={list.isFetching} error={errOf(list.error)}
        total={list.data?.totalElements ?? 0} page={paging.page} size={paging.size} emptyTitle="No bulk jobs"
        emptyMessage="Upload a CSV or generate a synthetic job." onPageChange={paging.onChange} onRetry={() => void list.refetch()}
      />
    </div>
  );
}
