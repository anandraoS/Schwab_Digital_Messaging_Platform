import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';

interface Common {
  label: string;
  error?: string | null;
  hint?: string;
}

function Wrap({ id, label, error, hint, children }: Common & { id: string; children: ReactNode }) {
  return (
    <div className={`field${error ? ' invalid' : ''}`}>
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && !error ? <div className="hint muted" id={`${id}-hint`}>{hint}</div> : null}
      {error ? <div className="field-err" id={`${id}-err`} role="alert">{error}</div> : null}
    </div>
  );
}

const describedBy = (id: string, error?: string | null, hint?: string): string | undefined =>
  error ? `${id}-err` : hint ? `${id}-hint` : undefined;

/** Labelled text input with an inline error (aria-invalid / aria-describedby wired). */
export function TextField({ label, error, hint, ...rest }: Common & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  return (
    <Wrap id={id} label={label} error={error} hint={hint}>
      <input id={id} aria-invalid={!!error} aria-describedby={describedBy(id, error, hint)} {...rest} />
    </Wrap>
  );
}

export function TextAreaField({ label, error, hint, ...rest }: Common & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const id = useId();
  return (
    <Wrap id={id} label={label} error={error} hint={hint}>
      <textarea id={id} aria-invalid={!!error} aria-describedby={describedBy(id, error, hint)} {...rest} />
    </Wrap>
  );
}

export function SelectField({
  label, error, hint, options, anyLabel, ...rest
}: Common & SelectHTMLAttributes<HTMLSelectElement> & { options: readonly string[]; anyLabel?: string }) {
  const id = useId();
  return (
    <Wrap id={id} label={label} error={error} hint={hint}>
      <select id={id} aria-invalid={!!error} aria-describedby={describedBy(id, error, hint)} {...rest}>
        {anyLabel !== undefined ? <option value="">{anyLabel}</option> : null}
        {options.map((o) => <option key={o} value={o}>{o}</option>)}
      </select>
    </Wrap>
  );
}

export function CheckField({ label, ...rest }: { label: string } & Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>) {
  const id = useId();
  return (
    <div className="check">
      <input id={id} type="checkbox" {...rest} />
      <label htmlFor={id}>{label}</label>
    </div>
  );
}
