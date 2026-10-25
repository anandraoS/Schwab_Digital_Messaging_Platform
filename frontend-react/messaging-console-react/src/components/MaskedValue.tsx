/**
 * Defensive masking. The backend already returns masked addresses; if a value that does not look
 * masked slips through (no '*'), mask it client-side so the UI never displays a full address.
 */
export function maskValue(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  if (value.includes('*')) {
    return value;
  }
  const at = value.indexOf('@');
  if (at > 0) {
    return value[0] + '***' + value.slice(at);
  }
  if (value.length <= 4) {
    return '***';
  }
  return value.slice(0, 4) + '***' + value.slice(-4);
}

export function MaskedValue({ value }: { value: string | null | undefined }) {
  const text = maskValue(value);
  return <span className="mono" aria-label={`Masked value ${text}`}>{text}</span>;
}
