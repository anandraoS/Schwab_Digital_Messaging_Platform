export type ChipTone = 'ok' | 'info' | 'warn' | 'bad' | 'neutral';

const TONES: Record<string, ChipTone> = {
  DELIVERED: 'ok', COMPLETED: 'ok', PUBLISHED: 'ok', CLOSED: 'ok', APPLIED: 'ok', ENABLED: 'ok',
  ACCEPTED: 'info', VALIDATED: 'info', QUEUED: 'info', PROCESSING: 'info', SENT_TO_PROVIDER: 'info',
  RUNNING: 'info', CREATED: 'info', SENT: 'info', PROVIDER_ACCEPTED: 'info', PENDING_APPROVAL: 'info',
  RETRY_PENDING: 'warn', DELIVERY_UNKNOWN: 'warn', HALF_OPEN: 'warn', COMPLETED_WITH_ERRORS: 'warn',
  DEFERRED: 'warn', DRAFT: 'warn', STOPPED: 'warn', ABANDONED: 'warn',
  FAILED: 'bad', DEAD_LETTERED: 'bad', REJECTED: 'bad', OPEN: 'bad', BOUNCED: 'bad', DISABLED: 'bad',
  CANCELLED: 'neutral', RETIRED: 'neutral', NONE: 'neutral',
};

export function toneFor(value: string | null | undefined): ChipTone {
  return (value && TONES[value.toUpperCase()]) || 'neutral';
}

/** Status pill. Colour is never the only signal: the label text is always rendered. */
export function StatusChip({ status }: { status: string | null | undefined }) {
  const label = (status ?? '').replace(/_/g, ' ') || 'UNKNOWN';
  return (
    <span className={`chip tone-${toneFor(status)}`} aria-label={`Status ${label}`} data-status={status ?? ''}>
      {label}
    </span>
  );
}
