import { useState, type FormEvent } from 'react';
import { ErrorBanner } from '../../components/ErrorBanner';
import { SelectField, TextAreaField, TextField } from '../../components/Fields';
import { toNormalizedError, type NormalizedError } from '../../core/http/errorMapper';
import { templatesApi } from '../../core/api/templates';
import {
  isLocale, isTemplateCode, parseSampleData, parseVariableList, placeholdersIn,
} from '../../core/forms/validators';
import { CATEGORIES, CHANNELS, type Category, type Channel, type TemplatePreviewResponse, type TemplateVersion } from '../../core/models';

export interface TemplateEditorValue {
  code: string;
  name: string;
  channel: Channel;
  category: Category;
  locale: string;
  subject: string;
  body: string;
  variables: string[];
  version?: number;
}

export type EditorMode = 'create' | 'version' | 'edit';

interface Props {
  mode: EditorMode;
  fixedChannel?: Channel | null;
  initial?: TemplateVersion | null;
  busy?: boolean;
  error?: NormalizedError | null;
  onSave: (v: TemplateEditorValue) => void;
  onCancel: () => void;
}

export function validateTemplateForm(
  f: { code: string; name: string; locale: string; body: string }, mode: EditorMode,
): Partial<Record<'code' | 'name' | 'locale' | 'body', string>> {
  const e: Partial<Record<'code' | 'name' | 'locale' | 'body', string>> = {};
  if (mode === 'create') {
    if (!f.code || !isTemplateCode(f.code)) e.code = 'Upper-case letters, digits and underscores (3-64)';
    if (!f.name.trim()) e.name = 'Name is required';
    else if (f.name.length > 200) e.name = 'Name is too long (max 200)';
  }
  if (!f.locale || !isLocale(f.locale)) e.locale = 'Use a locale like en or en-US';
  if (!f.body.trim()) e.body = 'Body is required';
  else if (f.body.length > 10000) e.body = 'Body is too long (max 10000)';
  return e;
}

export function TemplateEditor({ mode, fixedChannel = null, initial = null, busy = false, error = null, onSave, onCancel }: Props) {
  const [f, setF] = useState({
    code: '', name: '', channel: 'EMAIL' as Channel, category: 'TRANSACTIONAL' as Category,
    locale: initial?.locale ?? 'en-US', subject: initial?.subject ?? '', body: initial?.body ?? '',
    variables: initial ? initial.variables.join(', ') : '', sampleData: '',
  });
  const [touched, setTouched] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [preview, setPreview] = useState<TemplatePreviewResponse | null>(null);
  const [previewError, setPreviewError] = useState<NormalizedError | null>(null);

  const errors = validateTemplateForm(f, mode);
  const channel = fixedChannel ?? f.channel;
  const showSubject = channel === 'EMAIL';
  const declared = parseVariableList(f.variables);
  const undeclared = placeholdersIn(f.body).filter((p) => !declared.includes(p));
  const text = (k: keyof typeof f) => (e: { target: { value: string } }) => setF((x) => ({ ...x, [k]: e.target.value }));
  const show = (k: keyof typeof errors): string | undefined => (touched || (f[k] !== '' && k !== 'body') ? errors[k] : undefined);

  const submit = (e: FormEvent): void => {
    e.preventDefault();
    setTouched(true);
    if (Object.keys(errors).length > 0) return;
    onSave({
      code: f.code, name: f.name, channel, category: f.category, locale: f.locale,
      subject: showSubject ? f.subject : '', body: f.body, variables: declared, version: initial?.version,
    });
  };

  const doPreview = async (): Promise<void> => {
    setPreviewing(true);
    setPreviewError(null);
    try {
      setPreview(await templatesApi.preview({
        channel, subject: f.subject || undefined, body: f.body, variables: declared,
        sampleData: parseSampleData(f.sampleData), locale: f.locale,
      }));
    } catch (ex) {
      setPreviewError(toNormalizedError(ex));
    } finally {
      setPreviewing(false);
    }
  };

  const label = mode === 'create' ? 'Create template' : mode === 'edit' ? 'Save draft' : 'Create draft version';
  return (
    <form onSubmit={submit} aria-label="Template editor" noValidate>
      <div className="form-grid">
        {mode === 'create' ? (
          <>
            <TextField label="Code" name="code" value={f.code} onChange={text('code')} placeholder="TRADE_CONFIRMATION" error={show('code')} />
            <TextField label="Name" name="name" value={f.name} onChange={text('name')} placeholder="Trade confirmation" error={show('name')} />
            <SelectField label="Channel" name="channel" value={f.channel} onChange={text('channel')} options={CHANNELS} />
            <SelectField label="Category" name="category" value={f.category} onChange={text('category')} options={CATEGORIES} />
          </>
        ) : null}
        <TextField label="Locale" name="locale" value={f.locale} onChange={text('locale')} placeholder="en-US" disabled={mode === 'edit'} error={show('locale')} />
        {showSubject ? <TextField label="Subject" name="subject" value={f.subject} onChange={text('subject')} /> : null}
      </div>
      <TextAreaField label="Body template (double-brace placeholders)" name="body" rows={6} value={f.body} onChange={text('body')} error={touched ? errors.body : undefined} />
      <TextField
        label="Allowed variables (comma or space separated)" name="variables" value={f.variables} onChange={text('variables')}
        hint={undeclared.length ? `Undeclared placeholders in body: ${undeclared.join(', ')}` : undefined}
      />
      <details className="preview">
        <summary>Preview with sample data</summary>
        <TextAreaField label="Sample data (key=value per line)" name="sampleData" rows={3} value={f.sampleData} onChange={text('sampleData')} />
        <button type="button" className="btn" onClick={() => void doPreview()} disabled={previewing}>Render preview</button>
        <ErrorBanner error={previewError} retryable={false} />
        {preview ? (
          <div className="card" role="status">
            {preview.errors?.length ? <ul>{preview.errors.map((e, i) => <li key={i}>{e.field}: {e.message}</li>)}</ul> : null}
            {preview.subject ? <div><strong>Subject:</strong> {preview.subject}</div> : null}
            {preview.body ? <pre className="src">{preview.body}</pre> : null}
          </div>
        ) : null}
      </details>
      <ErrorBanner error={error} retryable={false} />
      <div className="toolbar-row">
        <button type="submit" className="btn primary" disabled={busy}>{label}</button>
        <button type="button" className="btn" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}
