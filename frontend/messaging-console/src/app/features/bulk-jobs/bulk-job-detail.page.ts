import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { EMPTY, Subscription, catchError, switchMap, takeWhile, timer } from 'rxjs';
import { BulkJobsService } from '../../core/api/bulk-jobs.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { BulkJob, BulkJobFailure, BulkJobStatus, PageResponse } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { ConfirmService } from '../../shared/confirm-dialog';
import { Column, DataTableComponent } from '../../shared/data-table';
import { ErrorBannerComponent } from '../../shared/error-banner';
import { PageChange } from '../../shared/pager';
import { StatusChipComponent } from '../../shared/status-chip';

export const POLL_INTERVAL_MS = 2000;
const ACTIVE: BulkJobStatus[] = ['CREATED', 'RUNNING'];

export function isActiveJob(j: Pick<BulkJob, 'status'>): boolean {
  return ACTIVE.includes(j.status);
}

export function progressPercent(j: Pick<BulkJob, 'processed' | 'total'>): number {
  return j.total > 0 ? Math.min(100, Math.round((j.processed / j.total) * 100)) : 0;
}

@Component({
  selector: 'app-bulk-job-detail-page',
  imports: [DatePipe, RouterLink, MatButtonModule, MatProgressBarModule, DataTableComponent, ErrorBannerComponent, StatusChipComponent],
  template: `
    <div class="page">
      <div class="toolbar-row">
        <h1 class="page-title grow">Bulk job <span class="mono">{{ id() }}</span></h1>
        <a mat-button routerLink="/bulk-jobs">Back to jobs</a>
        @if (job(); as j) {
          @if (active()) { <button mat-stroked-button type="button" (click)="stop()">Stop</button> }
          @if (j.status === 'STOPPED' || j.status === 'FAILED' || j.status === 'COMPLETED_WITH_ERRORS') { <button mat-stroked-button type="button" (click)="restart()">Restart</button> }
        }
      </div>
      <app-error-banner [error]="pollError()" (retry)="startPolling()" />
      <app-error-banner [error]="actionError()" [retryable]="false" />
      @if (job(); as j) {
        <section class="card" aria-label="Progress">
          <div class="toolbar-row"><strong>{{ j.name }}</strong> <app-status-chip [status]="j.status" />
            @if (active()) { <span class="muted" role="status">auto-refreshing every {{ intervalSeconds }}s</span> }</div>
          <mat-progress-bar mode="determinate" [value]="percent()" [attr.aria-label]="'Progress ' + percent() + ' percent'" />
          <p>{{ j.processed }} / {{ j.total }} processed ({{ percent() }}%) · succeeded {{ j.succeeded }} · failed {{ j.failed }} · duplicates {{ j.duplicate }}</p>
          <p class="muted">Started {{ j.startedAt ? (j.startedAt | date: 'medium') : '—' }} · finished {{ j.finishedAt ? (j.finishedAt | date: 'medium') : '—' }}
            @if (j.ratePerSecond) { · {{ j.ratePerSecond }}/s }</p>
        </section>
        <h2>Failures</h2>
        <app-data-table caption="Bulk job failures" [columns]="failureCols" [rows]="failures.data()?.content ?? []"
          [loading]="failures.loading()" [error]="failures.error()" [total]="failures.data()?.totalElements ?? 0"
          [page]="fPage()" [size]="fSize()" emptyTitle="No failed rows" emptyMessage="Rejected rows appear here with a reason."
          (pageChange)="onFailurePage($event)" (retry)="loadFailures()" />
      } @else if (!pollError()) { <p role="status">Loading job…</p> }
    </div>
  `,
  styles: `h2 { font: var(--mat-sys-title-medium); margin: 16px 0 8px; }`,
})
export class BulkJobDetailPage {
  private readonly api = inject(BulkJobsService);
  private readonly confirmSvc = inject(ConfirmService);
  private readonly destroyRef = inject(DestroyRef);

  readonly id = input.required<string>();
  readonly job = signal<BulkJob | null>(null);
  readonly pollError = signal<NormalizedError | null>(null);
  readonly actionError = signal<NormalizedError | null>(null);
  readonly failures = new Loadable<PageResponse<BulkJobFailure>>();
  readonly fPage = signal(0);
  readonly fSize = signal(20);
  readonly intervalSeconds = POLL_INTERVAL_MS / 1000;
  readonly active = computed(() => !!this.job() && isActiveJob(this.job()!));
  readonly percent = computed(() => (this.job() ? progressPercent(this.job()!) : 0));
  private poll?: Subscription;

  readonly failureCols: Column<BulkJobFailure>[] = [
    { key: 'lineNo', header: 'Line' }, { key: 'reason', header: 'Reason' }, { key: 'rawLineHash', header: 'Row hash', type: 'mono' },
  ];

  constructor() {
    effect(() => {
      this.id();
      untracked(() => {
        this.startPolling();
        this.loadFailures();
      });
    });
    this.destroyRef.onDestroy(() => this.poll?.unsubscribe());
  }

  /** Polls until the job leaves CREATED/RUNNING; stops with the component (takeUntilDestroyed). */
  startPolling(): void {
    this.poll?.unsubscribe();
    this.pollError.set(null);
    this.poll = timer(0, POLL_INTERVAL_MS).pipe(
      switchMap(() => this.api.get(this.id(), true).pipe(
        catchError((e: unknown) => { this.pollError.set(toNormalizedError(e)); return EMPTY; }),
      )),
      takeWhile((j) => isActiveJob(j), true),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((j) => {
      this.pollError.set(null);
      const wasActive = this.active();
      this.job.set(j);
      if (wasActive && !isActiveJob(j)) {
        this.loadFailures(); // job just finished: refresh the failure list
      }
    });
  }

  loadFailures(): void {
    this.failures.load(this.api.failures(this.id(), { page: this.fPage(), size: this.fSize() }));
  }

  onFailurePage(e: PageChange): void {
    this.fPage.set(e.page);
    this.fSize.set(e.size);
    this.loadFailures();
  }

  stop(): void {
    this.confirmSvc.confirm({ title: 'Stop bulk job?', message: 'Rows already submitted continue through the pipeline. You can restart the job later.', confirmLabel: 'Stop', danger: true })
      .subscribe((ok) => ok && this.act(this.api.stop(this.id())));
  }

  restart(): void {
    this.act(this.api.restart(this.id()));
  }

  private act(call: ReturnType<BulkJobsService['stop']>): void {
    this.actionError.set(null);
    call.subscribe({
      next: () => this.startPolling(),
      error: (e: unknown) => this.actionError.set(toNormalizedError(e)),
    });
  }
}
