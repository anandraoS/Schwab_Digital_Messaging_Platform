import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { MessagesService } from '../../core/api/messages.service';
import { ACCESS } from '../../core/auth/access';
import { AuthService } from '../../core/auth/auth.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { HistoryEntry, MessageDetail } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { ConfirmService } from '../../shared/confirm-dialog';
import { Column, DataTableComponent } from '../../shared/data-table';
import { ErrorBannerComponent } from '../../shared/error-banner';
import { MaskedValueComponent } from '../../shared/masked-value';
import { StatusChipComponent } from '../../shared/status-chip';
import { TimelineComponent } from '../../shared/timeline';

export const CANCELLABLE = ['VALIDATED', 'QUEUED', 'RETRY_PENDING'];
export const REQUEUEABLE = ['FAILED', 'DEAD_LETTERED'];

@Component({
  selector: 'app-message-detail-page',
  imports: [
    DatePipe, RouterLink, MatButtonModule, DataTableComponent, ErrorBannerComponent, MaskedValueComponent,
    StatusChipComponent, TimelineComponent,
  ],
  template: `
    <div class="page">
      <div class="toolbar-row">
        <h1 class="page-title grow">Message <span class="mono">{{ id() }}</span></h1>
        <a mat-button routerLink="/messages">Back to list</a>
        <button mat-stroked-button type="button" (click)="reload()">Refresh</button>
        @if (canCancel()) { <button mat-stroked-button type="button" (click)="cancel()">Cancel message</button> }
        @if (canRequeue()) { <button mat-stroked-button type="button" (click)="requeue()">Requeue</button> }
      </div>
      <app-error-banner [error]="detail.error()" (retry)="reload()" />
      <app-error-banner [error]="actionError()" [retryable]="false" />
      @if (notice()) { <p role="status" class="notice">{{ notice() }}</p> }
      @if (detail.loading() && !m()) { <p role="status">Loading message…</p> }
      @if (m(); as d) {
        <section class="card summary" aria-label="Summary">
          <dl>
            <div><dt>Status</dt><dd><app-status-chip [status]="d.status" /> @if (d.terminal) { <span class="muted">(terminal)</span> }</dd></div>
            @if (d.statusReason) { <div><dt>Reason</dt><dd>{{ d.statusReason }}</dd></div> }
            <div><dt>Channel</dt><dd>{{ d.channel }}@if (d.channelUsed && d.channelUsed !== d.channel) { → {{ d.channelUsed }} (fallback) }</dd></div>
            <div><dt>Category / priority</dt><dd>{{ d.category }} / {{ d.priority }}</dd></div>
            <div><dt>Recipient</dt><dd><span class="mono">{{ d.recipient.externalRef ?? d.recipient.id }}</span> <app-masked-value [value]="d.recipient.maskedAddress" /></dd></div>
            <div><dt>Template</dt><dd>{{ d.template.code }} v{{ d.template.versionNo ?? '?' }} ({{ d.template.locale ?? '—' }})</dd></div>
            <div><dt>Client reference</dt><dd>{{ d.clientReference ?? '—' }}</dd></div>
            <div><dt>Correlation id</dt><dd class="mono">{{ d.correlationId }}</dd></div>
            <div><dt>Idempotency key</dt><dd class="mono">{{ d.idempotencyKey ?? '—' }}</dd></div>
            <div><dt>Attempts</dt><dd>{{ d.attemptCount }} / {{ d.maxAttempts }}</dd></div>
            <div><dt>Next attempt</dt><dd>{{ d.nextAttemptAt ? (d.nextAttemptAt | date: 'medium') : '—' }}</dd></div>
            <div><dt>Scheduled / expires</dt><dd>{{ d.scheduledAt ? (d.scheduledAt | date: 'medium') : '—' }} / {{ d.expiresAt ? (d.expiresAt | date: 'medium') : '—' }}</dd></div>
            <div><dt>Created / updated</dt><dd>{{ d.createdAt | date: 'medium' }} / {{ d.updatedAt | date: 'medium' }}</dd></div>
          </dl>
          <p class="muted">Message content is never returned by the API and is not displayed here.</p>
        </section>

        <div class="cols">
          <section class="card"><h2>Lifecycle timeline</h2>
            <app-error-banner [error]="history.error()" (retry)="loadHistory()" />
            @if (history.loading()) { <p role="status">Loading timeline…</p> }
            @if (history.data(); as h) {
              @if (h.length) { <app-timeline [entries]="h" /> } @else { <p class="muted">No history entries yet.</p> }
            }
          </section>
          <section class="card"><h2>Attempts</h2>
            <app-data-table caption="Attempts" [columns]="attemptCols" [rows]="d.attempts" emptyTitle="No attempts yet" emptyMessage="" />
            <h2>Delivery events</h2>
            <app-data-table caption="Delivery events" [columns]="eventCols" [rows]="d.deliveryEvents" emptyTitle="No delivery events" emptyMessage="" />
          </section>
        </div>
      }
    </div>
  `,
  styles: `
    dl { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 8px 24px; margin: 0; }
    dl > div { display: flex; gap: 8px; } dt { font-weight: 600; min-width: 140px; } dd { margin: 0; word-break: break-all; }
    .cols { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(380px, 1fr)); margin-top: 16px; }
    h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; } .notice { color: #0b5a22; }
    @media (max-width: 600px) { .cols { grid-template-columns: 1fr; } }
  `,
})
export class MessageDetailPage {
  private readonly api = inject(MessagesService);
  private readonly auth = inject(AuthService);
  private readonly confirmSvc = inject(ConfirmService);

  readonly id = input.required<string>();
  readonly detail = new Loadable<MessageDetail>();
  readonly history = new Loadable<HistoryEntry[]>();
  readonly m = this.detail.data;
  readonly actionError = signal<NormalizedError | null>(null);
  readonly notice = signal('');

  readonly canCancel = computed(() => !!this.m() && CANCELLABLE.includes(this.m()!.status) && this.auth.hasAnyRole(ACCESS.cancel));
  readonly canRequeue = computed(() => !!this.m() && REQUEUEABLE.includes(this.m()!.status) && this.auth.hasAnyRole(ACCESS.requeue));

  readonly attemptCols: Column[] = [
    { key: 'attemptNo', header: '#' }, { key: 'channel', header: 'Channel' }, { key: 'providerId', header: 'Provider' },
    { key: 'outcome', header: 'Outcome', type: 'status' }, { key: 'errorCode', header: 'Error' },
    { key: 'latencyMs', header: 'ms' }, { key: 'startedAt', header: 'Started', type: 'date' },
  ];
  readonly eventCols: Column[] = [
    { key: 'type', header: 'Type', type: 'status' }, { key: 'providerId', header: 'Provider' },
    { key: 'occurredAt', header: 'Occurred', type: 'date' }, { key: 'applied', header: 'Applied', type: 'bool' },
    { key: 'ignoredReason', header: 'Ignored reason' },
  ];

  constructor() {
    effect(() => {
      this.id();
      this.reload();
    });
  }

  reload(): void {
    this.detail.load(this.api.get(this.id()));
    this.loadHistory();
  }

  loadHistory(): void {
    this.history.load(this.api.history(this.id()));
  }

  cancel(): void {
    this.run('Cancel message', 'Cancel this message? It will not be delivered.', 'Cancel message', () => this.api.cancel(this.id()), 'Message cancelled.');
  }

  requeue(): void {
    this.run('Requeue message', 'Put this message back on the queue for another delivery attempt?', 'Requeue', () => this.api.requeue(this.id()), 'Message requeued.');
  }

  private run(title: string, message: string, confirmLabel: string, call: () => ReturnType<MessagesService['cancel']>, done: string): void {
    this.actionError.set(null);
    this.notice.set('');
    this.confirmSvc.confirm({ title, message, confirmLabel, danger: true }).subscribe((ok) => {
      if (!ok) {
        return;
      }
      call().subscribe({
        next: () => { this.notice.set(done); this.reload(); },
        error: (e: unknown) => this.actionError.set(toNormalizedError(e)),
      });
    });
  }
}
