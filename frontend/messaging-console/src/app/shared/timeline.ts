import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { HistoryEntry } from '../core/models/api.models';
import { StatusChipComponent } from './status-chip';

/** Vertical lifecycle timeline, ordered oldest-first. All text is interpolated (never innerHTML). */
@Component({
  selector: 'app-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, StatusChipComponent],
  template: `
    <ol class="tl" aria-label="Message lifecycle timeline">
      @for (e of sorted(); track $index) {
        <li class="item" [attr.data-kind]="e.kind">
          <span class="dot" aria-hidden="true"></span>
          <div class="body">
            <div class="row">
              <strong>{{ e.summary }}</strong>
              @if (statusOf(e); as st) { <app-status-chip [status]="st" /> }
              <span class="kind muted">{{ e.kind }}</span>
            </div>
            <time class="muted" [attr.datetime]="e.at">{{ e.at | date: 'yyyy-MM-dd HH:mm:ss.SSS' }}</time>
            @if (actorOf(e); as actor) { <span class="muted"> · {{ actor }}</span> }
            @if (detailText(e); as text) { <div class="detail">{{ text }}</div> }
          </div>
        </li>
      }
    </ol>
  `,
  styles: `
    .tl { list-style: none; margin: 0; padding: 0 0 0 14px; border-left: 2px solid var(--mat-sys-outline-variant); }
    .item { position: relative; padding: 0 0 14px 16px; }
    .dot { position: absolute; left: -22px; top: 4px; width: 12px; height: 12px; border-radius: 50%; background: var(--mat-sys-primary); border: 2px solid #fff; }
    .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .kind { font-size: 11px; text-transform: uppercase; }
    .detail { margin-top: 2px; font-size: 13px; }
  `,
})
export class TimelineComponent {
  readonly entries = input<HistoryEntry[]>([]);
  readonly sorted = computed(() =>
    [...this.entries()].sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime()),
  );

  /** Target status of a lifecycle transition (`detail.to`), or the provider receipt type. */
  statusOf(e: HistoryEntry): string | null {
    const to = e.detail?.['to'];
    if (typeof to === 'string' && to) {
      return to;
    }
    return e.kind === 'DELIVERY_EVENT' ? e.type : null;
  }

  actorOf(e: HistoryEntry): string | null {
    const a = e.detail?.['actor'];
    return typeof a === 'string' && a ? a : null;
  }

  /** Remaining detail fields as "key: value" pairs (actor and target status are shown elsewhere). */
  detailText(e: HistoryEntry): string {
    const d = e.detail;
    if (!d) {
      return '';
    }
    return Object.entries(d)
      .filter(([k, v]) => k !== 'actor' && k !== 'to' && v !== null && v !== undefined && v !== '')
      .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
      .join(' · ');
  }
}
