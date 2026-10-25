import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

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
@Component({
  selector: 'app-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip" [class]="'chip tone-' + tone()" [attr.aria-label]="'Status ' + label()">{{ label() }}</span>`,
  styles: `
    .chip { display: inline-block; padding: 2px 10px; border-radius: 12px; font-size: 12px; font-weight: 600;
      letter-spacing: .02em; border: 1px solid transparent; white-space: nowrap; }
    .tone-ok { background: #e3f4e7; color: #0b5a22; border-color: #8ccf9d; }
    .tone-info { background: #e3eefc; color: #0b3d91; border-color: #99bdf0; }
    .tone-warn { background: #fff3d6; color: #6b4500; border-color: #e6c36a; }
    .tone-bad { background: #fde7e7; color: #8a0f0f; border-color: #eba0a0; }
    .tone-neutral { background: #eceff1; color: #37474f; border-color: #b0bec5; }
  `,
})
export class StatusChipComponent {
  readonly status = input<string | null | undefined>('');
  readonly tone = computed(() => toneFor(this.status()));
  readonly label = computed(() => (this.status() ?? '').replace(/_/g, ' ') || 'UNKNOWN');
}
