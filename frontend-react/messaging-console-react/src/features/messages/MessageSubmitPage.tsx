import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { CheckField, SelectField, TextField } from '../../components/Fields';
import { ErrorBanner } from '../../components/ErrorBanner';
import { StatusChip } from '../../components/StatusChip';
import { messagesApi } from '../../core/api/messages';
import { templatesApi } from '../../core/api/templates';
import { MAX_PUSH_TTL_SECONDS } from '../../core/forms/validators';
import { newCorrelationId } from '../../core/http/client';
import { toNormalizedError, type NormalizedError } from '../../core/http/errorMapper';
import { CATEGORIES, CHANNELS, PRIORITIES, type Channel, type SubmitResult } from '../../core/models';
import { EMPTY_SUBMIT_FORM, buildSubmitRequest, validateSubmit, type SubmitFormValue } from './submitRequest';

export function MessageSubmitPage() {
  const [v, setV] = useState<SubmitFormValue>(EMPTY_SUBMIT_FORM);
  const [showErrors, setShowErrors] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState(newCorrelationId());
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<SubmitResult | null>(null);
  const [error, setError] = useState<NormalizedError | null>(null);

  const templates = useQuery({
    queryKey: ['templates-published', v.channel],
    queryFn: () => templatesApi.list({ channel: v.channel, status: 'PUBLISHED', size: 200 }),
  });
  const errors = validateSubmit(v);
  const err = (k: keyof typeof errors): string | undefined => (showErrors ? errors[k] : undefined);
  const patch = (p: Partial<SubmitFormValue>): void => setV((x) => ({ ...x, ...p }));
  const text = (k: keyof SubmitFormValue) => (e: { target: { value: string } }) => patch({ [k]: e.target.value } as Partial<SubmitFormValue>);

  const setChannel = (c: Channel): void => patch({ channel: c, fallbackChannels: v.fallbackChannels.filter((x) => x !== c) });

  const pickTemplate = async (code: string): Promise<void> => {
    patch({ templateCode: code });
    const t = templates.data?.content.find((x) => x.code === code);
    if (!t) return;
    try {
      const d = await templatesApi.get(t.id);
      const first = d.versions.find((x) => x.status === 'PUBLISHED');
      if (first) {
        setV((x) => ({
          ...x,
          locale: first.locale,
          variables: x.variables.length === 0 ? first.variables.map((k) => ({ key: k, value: '' })) : x.variables,
        }));
      }
    } catch {
      /* the picker is optional; the code can still be typed */
    }
  };

  const regenerateKey = (): void => {
    setIdempotencyKey(newCorrelationId());
    setResult(null);
    setError(null);
  };

  const reset = (): void => {
    setV(EMPTY_SUBMIT_FORM);
    setShowErrors(false);
    regenerateKey();
  };

  const submit = async (e: FormEvent): Promise<void> => {
    e.preventDefault();
    setShowErrors(true);
    if (Object.keys(errors).length > 0) {
      return;
    }
    setSubmitting(true);
    setError(null);
    setResult(null);
    try {
      setResult(await messagesApi.submit(buildSubmitRequest(v), idempotencyKey));
    } catch (ex) {
      setError(toNormalizedError(ex));
    } finally {
      setSubmitting(false);
    }
  };

  const published = templates.data?.content ?? [];
  const setVar = (i: number, p: Partial<{ key: string; value: string }>): void =>
    patch({ variables: v.variables.map((r, j) => (j === i ? { ...r, ...p } : r)) });

  return (
    <div className="page">
      <h1 className="page-title">Submit a message</h1>
      <p className="muted">Message bodies are rendered server-side from templates; this console never shows or sends body text.</p>

      {result ? (
        <div className="card result-ok" role="status">
          <strong>{result.replay ? `Idempotent replay (HTTP ${result.httpStatus})` : `Accepted (HTTP ${result.httpStatus})`}</strong>
          <div>
            Message <Link to={`/messages/${encodeURIComponent(result.body.id)}`} className="mono">{result.body.id}</Link>{' '}
            <StatusChip status={result.body.status} /> on {result.body.channel}
          </div>
          <div className="mono">correlationId={result.body.correlationId}</div>
        </div>
      ) : null}
      <ErrorBanner error={error} retryable={false} />

      <form onSubmit={(e) => void submit(e)} noValidate aria-label="Submit message">
        <section className="card">
          <h2>Recipient</h2>
          <div className="form-grid">
            <TextField label="Recipient id" name="recipientId" value={v.recipientId} onChange={text('recipientId')} placeholder="rcp-001" />
            <TextField label="or external reference" name="externalRef" value={v.externalRef} onChange={text('externalRef')} placeholder="CUST-001" />
            <TextField label="Client reference" name="clientReference" value={v.clientReference} onChange={text('clientReference')} maxLength={100} />
          </div>
          {err('recipient') ? <div className="field-err" role="alert">{err('recipient')}</div> : null}
        </section>

        <section className="card">
          <h2>Delivery</h2>
          <div className="form-grid">
            <SelectField label="Channel" name="channel" value={v.channel} onChange={(e) => setChannel(e.target.value as Channel)} options={CHANNELS} />
            <SelectField label="Category" name="category" value={v.category} onChange={text('category')} options={CATEGORIES} />
            <SelectField label="Priority" name="priority" value={v.priority} onChange={text('priority')} options={PRIORITIES} />
          </div>
          <fieldset className="checks" aria-label="Fallback channels">
            <legend className="muted">Fallback channels (never bypass consent)</legend>
            {CHANNELS.filter((c) => c !== v.channel).map((c) => (
              <CheckField
                key={c}
                label={c}
                name={`fallback-${c}`}
                checked={v.fallbackChannels.includes(c)}
                onChange={(e) => patch({ fallbackChannels: e.target.checked ? [...v.fallbackChannels, c] : v.fallbackChannels.filter((x) => x !== c) })}
              />
            ))}
          </fieldset>
          {err('fallback') ? <div className="field-err" role="alert">{err('fallback')}</div> : null}

          {v.channel === 'EMAIL' ? (
            <div className="form-grid">
              <TextField label="Reply-to" name="replyTo" type="email" value={v.replyTo} onChange={text('replyTo')} error={err('replyTo')} />
            </div>
          ) : null}
          {v.channel === 'SMS' ? (
            <div className="form-grid">
              <TextField label="Sender id" name="senderId" value={v.senderId} onChange={text('senderId')} placeholder="ACME" hint="3-11 letters/digits" error={err('senderId')} />
            </div>
          ) : null}
          {v.channel === 'PUSH' ? (
            <div className="form-grid">
              <TextField label="Collapse key" name="collapseKey" value={v.collapseKey} onChange={text('collapseKey')} maxLength={64} />
              <TextField label="TTL (seconds)" name="ttlSeconds" type="number" value={v.ttlSeconds} onChange={text('ttlSeconds')} error={err('ttlSeconds')} hint={`1 to ${MAX_PUSH_TTL_SECONDS}`} />
            </div>
          ) : null}
        </section>

        <section className="card">
          <h2>Template</h2>
          <div className="form-grid">
            <div className="field">
              <label htmlFor="template-picker">Published template</label>
              <select id="template-picker" value={published.some((t) => t.code === v.templateCode) ? v.templateCode : ''} onChange={(e) => void pickTemplate(e.target.value)}>
                <option value="">Choose…</option>
                {published.map((t) => <option key={t.id} value={t.code}>{t.code}</option>)}
              </select>
              <div className="hint muted">{published.length} published for {v.channel}</div>
            </div>
            <TextField label="Template code" name="templateCode" required value={v.templateCode} onChange={text('templateCode')} error={err('templateCode')} />
            <TextField label="Locale" name="locale" required value={v.locale} onChange={text('locale')} placeholder="en-US" error={err('locale')} />
            <TextField label="Pinned version (optional)" name="templateVersion" type="number" value={v.templateVersion} onChange={text('templateVersion')} hint="Empty = latest PUBLISHED at accept time" error={err('templateVersion')} />
          </div>
        </section>

        <section className="card">
          <h2>Variables</h2>
          {v.variables.map((row, i) => (
            <div key={i} className="var-row">
              <TextField label="Key" aria-label={`Variable key ${i + 1}`} name={`var-key-${i}`} value={row.key} onChange={(e) => setVar(i, { key: e.target.value })}
                error={showErrors && !/^$|^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(row.key) ? 'Letters, digits, underscore; start with a letter' : undefined} />
              <TextField label="Value" aria-label={`Variable value ${i + 1}`} name={`var-value-${i}`} value={row.value} onChange={(e) => setVar(i, { value: e.target.value })} />
              <button type="button" className="btn" aria-label={`Remove variable ${i + 1}`} onClick={() => patch({ variables: v.variables.filter((_, j) => j !== i) })}>Remove</button>
            </div>
          ))}
          {err('variableKeys') ? <div className="field-err" role="alert">{err('variableKeys')}</div> : null}
          <button type="button" className="btn" onClick={() => patch({ variables: [...v.variables, { key: '', value: '' }] })}>Add variable</button>
        </section>

        <section className="card">
          <h2>Schedule (optional)</h2>
          <div className="form-grid">
            <TextField label="Send at" name="sendAt" type="datetime-local" value={v.sendAt} onChange={text('sendAt')} error={err('sendAt')} />
            <TextField label="Expires at" name="expiresAt" type="datetime-local" value={v.expiresAt} onChange={text('expiresAt')} />
          </div>
          {err('expiry') ? <div className="field-err" role="alert">{err('expiry')}</div> : null}
        </section>

        <section className="card">
          <h2>Idempotency</h2>
          <div className="toolbar-row">
            <code className="mono" data-testid="idem-key">{idempotencyKey}</code>
            <button type="button" className="btn" onClick={regenerateKey}>New key</button>
          </div>
          <p className="muted">
            Resubmitting with the same key returns the original response (HTTP 200, Idempotent-Replay). Use &quot;New key&quot; to send a different message.
          </p>
        </section>

        <div className="toolbar-row">
          <button type="submit" className="btn primary" disabled={submitting}>{submitting ? 'Submitting…' : 'Submit message'}</button>
          <button type="button" className="btn" onClick={reset}>Reset</button>
        </div>
      </form>
    </div>
  );
}
