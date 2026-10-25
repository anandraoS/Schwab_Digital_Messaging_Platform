import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { DataTable, type Column } from '../../components/DataTable';
import { ErrorBanner } from '../../components/ErrorBanner';
import { CheckField, TextField } from '../../components/Fields';
import { errOf } from '../../components/hooks';
import { recipientsApi } from '../../core/api/recipients';
import { ACCESS } from '../../core/auth/access';
import { useAuth } from '../../core/auth/session';
import { isFallbackOrder, isQuietTime, parseChannels, quietPairValid } from '../../core/forms/validators';
import { toNormalizedError, type NormalizedError } from '../../core/http/errorMapper';
import type { RecipientPreferences, RecipientSummary, UpdatePreferencesRequest } from '../../core/models';

export interface PrefsForm {
  marketingOptIn: boolean; emailEnabled: boolean; smsEnabled: boolean; pushEnabled: boolean;
  quietStart: string; quietEnd: string; fallbackOrder: string;
}

export function toForm(p: RecipientPreferences): PrefsForm {
  return {
    marketingOptIn: p.marketingOptIn, emailEnabled: p.emailEnabled, smsEnabled: p.smsEnabled, pushEnabled: p.pushEnabled,
    quietStart: p.quietStart ?? '', quietEnd: p.quietEnd ?? '', fallbackOrder: (p.fallbackOrder ?? []).join(','),
  };
}

export function validatePrefs(f: PrefsForm): Partial<Record<'quietStart' | 'quietEnd' | 'quietPair' | 'fallbackOrder', string>> {
  const e: Partial<Record<'quietStart' | 'quietEnd' | 'quietPair' | 'fallbackOrder', string>> = {};
  if (!isQuietTime(f.quietStart)) e.quietStart = 'Use 24h HH:mm or leave blank';
  if (!isQuietTime(f.quietEnd)) e.quietEnd = 'Use 24h HH:mm or leave blank';
  if (!quietPairValid(f.quietStart, f.quietEnd)) e.quietPair = 'Set both quiet-hours ends, or leave both blank for the default window.';
  if (!isFallbackOrder(f.fallbackOrder)) e.fallbackOrder = 'Comma-separated, unique: EMAIL, SMS, PUSH';
  return e;
}

/** Builds the PUT body. Blank quiet hours are sent as null (= default window); `version` is the loaded one. */
export function toUpdateRequest(f: PrefsForm, loaded: RecipientPreferences): UpdatePreferencesRequest {
  return {
    marketingOptIn: f.marketingOptIn, emailEnabled: f.emailEnabled, smsEnabled: f.smsEnabled, pushEnabled: f.pushEnabled,
    quietStart: f.quietStart.trim() || null, quietEnd: f.quietEnd.trim() || null,
    timeZone: loaded.timeZone ?? null, fallbackOrder: parseChannels(f.fallbackOrder), version: loaded.version,
  };
}

const columns: Column<RecipientSummary>[] = [
  { key: 'id', header: 'Id', type: 'mono' }, { key: 'externalRef', header: 'External ref' },
  { key: 'displayName', header: 'Name' },
  { key: 'maskedAddress', header: 'Address', type: 'masked', value: (r) => r.maskedEmail ?? r.maskedPhone ?? '' },
  { key: 'locale', header: 'Locale' },
];

export function PreferencesPage() {
  const { hasAnyRole } = useAuth();
  const qc = useQueryClient();
  const canEdit = hasAnyRole(ACCESS.preferencesEdit);
  const [q, setQ] = useState('');
  const [searched, setSearched] = useState<string | null>(null);
  const [selected, setSelected] = useState<RecipientSummary | null>(null);
  /** The user's unsaved edits laid over the loaded values (cleared on save / discard). */
  const [edits, setEdits] = useState<Partial<PrefsForm>>({});
  const [touched, setTouched] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState<NormalizedError | null>(null);
  const [conflict, setConflict] = useState<NormalizedError | null>(null);

  const results = useQuery({
    queryKey: ['recipients', searched],
    queryFn: () => recipientsApi.search((searched ?? '').trim(), 0, 20),
    enabled: searched !== null,
  });
  const prefs = useQuery({
    queryKey: ['preferences', selected?.id],
    queryFn: () => recipientsApi.getPreferences(selected!.id),
    enabled: !!selected,
    gcTime: 0,
  });
  const loaded = prefs.data;
  const form: PrefsForm | null = loaded ? { ...toForm(loaded), ...edits } : null;
  const errors = form ? validatePrefs(form) : {};
  const patch = (p: Partial<PrefsForm>): void => { setEdits((e) => ({ ...e, ...p })); setSaved(false); };

  const select = (r: RecipientSummary): void => {
    setSelected(r);
    setEdits({});
    setConflict(null);
    setSaveError(null);
    setSaved(false);
    setTouched(false);
    if (selected?.id === r.id) void prefs.refetch();
  };

  /** After a 409: reload the latest record; `reapply` keeps the user's edits laid over the fresh values. */
  const reload = async (reapply: boolean): Promise<void> => {
    setConflict(null);
    setSaveError(null);
    if (!reapply) setEdits({});
    await prefs.refetch();
  };

  const save = async (e: FormEvent): Promise<void> => {
    e.preventDefault();
    setTouched(true);
    if (!selected || !loaded || !form || Object.keys(errors).length > 0) return;
    setSaving(true);
    setSaved(false);
    setSaveError(null);
    setConflict(null);
    try {
      const p = await recipientsApi.updatePreferences(selected.id, toUpdateRequest(form, loaded));
      qc.setQueryData(['preferences', selected.id], p);
      setEdits({});
      setSaved(true);
    } catch (ex) {
      const n = toNormalizedError(ex);
      if (n.code === 'CONFLICT_OPTIMISTIC_LOCK') setConflict(n);
      else setSaveError(n);
    } finally {
      setSaving(false);
    }
  };

  const shown = (k: keyof typeof errors): string | undefined => (touched ? errors[k] : undefined);

  return (
    <div className="page">
      <h1 className="page-title">Recipient preferences</h1>
      <form className="toolbar-row" onSubmit={(e) => { e.preventDefault(); setSearched(q); }} aria-label="Recipient search">
        <TextField label="Search recipients" name="q" value={q} onChange={(e) => setQ(e.target.value)} placeholder="name, reference or id" />
        <button type="submit" className="btn primary">Search</button>
      </form>
      <DataTable
        caption="Recipients" columns={columns} rows={results.data?.content ?? []} loading={results.isFetching}
        error={errOf(results.error)} emptyTitle="No recipients" emptyMessage="Search to find a recipient."
        rowActions={(r) => <button type="button" className="btn" onClick={() => select(r)}>Edit preferences</button>}
        onRetry={() => void results.refetch()}
      />

      {selected ? (
        <section className="card" aria-label="Preference editor" style={{ marginTop: 16 }}>
          <h2>Preferences for <span className="mono">{selected.externalRef}</span> ({selected.id})</h2>
          <ErrorBanner error={errOf(prefs.error)} onRetry={() => void prefs.refetch()} />
          {conflict ? (
            <div className="conflict" role="alert">
              <strong>Edit conflict.</strong> {conflict.message}
              <div className="toolbar-row">
                <button type="button" className="btn" onClick={() => void reload(false)}>Reload latest (discard my edits)</button>
                <button type="button" className="btn primary" onClick={() => void reload(true)}>Reload latest and re-apply my edits</button>
              </div>
            </div>
          ) : null}
          <ErrorBanner error={saveError} retryable={false} />
          {prefs.isLoading ? <p role="status">Loading preferences…</p> : null}
          {form && loaded ? (
            <form onSubmit={(e) => void save(e)} aria-label="Preferences form" noValidate>
              <fieldset className="checks" disabled={!canEdit}>
                <legend className="sr-only">Channel consent</legend>
                <CheckField label="Marketing opt-in" name="marketingOptIn" checked={form.marketingOptIn} onChange={(e) => patch({ marketingOptIn: e.target.checked })} />
                <CheckField label="E-mail enabled" name="emailEnabled" checked={form.emailEnabled} onChange={(e) => patch({ emailEnabled: e.target.checked })} />
                <CheckField label="SMS enabled" name="smsEnabled" checked={form.smsEnabled} onChange={(e) => patch({ smsEnabled: e.target.checked })} />
                <CheckField label="Push enabled" name="pushEnabled" checked={form.pushEnabled} onChange={(e) => patch({ pushEnabled: e.target.checked })} />
              </fieldset>
              <div className="form-grid">
                <TextField label="Quiet hours start (HH:mm)" name="quietStart" value={form.quietStart} disabled={!canEdit} placeholder="default 21:00"
                  onChange={(e) => patch({ quietStart: e.target.value })} error={shown('quietStart')} />
                <TextField label="Quiet hours end (HH:mm)" name="quietEnd" value={form.quietEnd} disabled={!canEdit} placeholder="default 08:00"
                  onChange={(e) => patch({ quietEnd: e.target.value })} error={shown('quietEnd')} />
                <TextField label="Fallback order" name="fallbackOrder" value={form.fallbackOrder} disabled={!canEdit} placeholder="EMAIL,SMS,PUSH"
                  onChange={(e) => patch({ fallbackOrder: e.target.value })} error={shown('fallbackOrder')} />
              </div>
              {shown('quietPair') ? <p className="field-err" role="alert">{shown('quietPair')}</p> : null}
              <p className="muted">Loaded version: {loaded.version ?? 'none (defaults)'}. Saves are rejected if someone else changed the record first.</p>
              {canEdit ? (
                <button type="submit" className="btn primary" disabled={saving}>{saving ? 'Saving…' : 'Save preferences'}</button>
              ) : <p className="muted">Your role can view preferences but not edit them.</p>}
              {saved ? <span role="status" className="ok"> Saved.</span> : null}
            </form>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
