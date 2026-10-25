import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { RouterLink } from '@angular/router';
import { EMPTY, catchError, filter, switchMap, timer } from 'rxjs';
import { OpsService } from '../../core/api/ops.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { PRIORITIES, QueueStats } from '../../core/models/api.models';
import { BarChartComponent, ChartDatum } from '../../shared/charts';
import { ErrorBannerComponent } from '../../shared/error-banner';

export const QUEUE_REFRESH_MS = 5000;

/** Human-readable age, e.g. 3725 -> "1h 2m 5s". */
export function formatAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || seconds < 0) {
    return '—';
  }
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return [h ? `${h}h` : '', h || m ? `${m}m` : '', `${r}s`].filter(Boolean).join(' ');
}

@Component({
  selector: 'app-queues-page',
  imports: [RouterLink, MatButtonModule, MatCheckboxModule, BarChartComponent, ErrorBannerComponent],
  template: `
    <div class="page">
      <div class="toolbar-row">
        <h1 class="page-title grow">Queues</h1>
        <mat-checkbox [checked]="auto()" (change)="auto.set($event.checked)">Auto-refresh ({{ seconds }}s)</mat-checkbox>
        <button mat-stroked-button type="button" (click)="refresh()">Refresh now</button>
      </div>
      <app-error-banner [error]="error()" (retry)="refresh()" />
      @if (stats(); as s) {
        @if (s.consumersPaused) { <p class="paused" role="status">Consumers are paused.</p> }
        <div class="grid kpis">
          <section class="card"><div class="muted">In flight</div><div class="kpi">{{ s.inFlight }}</div></section>
          <section class="card"><div class="muted">Consumers</div><div class="kpi">{{ s.consumers }}</div></section>
          <section class="card"><div class="muted">Retry pending</div><div class="kpi">{{ s.retryPending }}</div></section>
          <section class="card"><div class="muted">Dead-letter depth</div><div class="kpi">{{ s.dlqDepth }}</div><a routerLink="/dead-letters">View dead letters</a></section>
          <section class="card"><div class="muted">Oldest message age</div><div class="kpi">{{ age() }}</div></section>
        </div>
        <section class="card"><h2>Queue depth by priority</h2><app-bar-chart [data]="depth()" title="Queue depth by priority" /></section>
        <p class="muted">Last updated {{ updated() }}</p>
      } @else if (!error()) { <p role="status">Loading queue statistics…</p> }
    </div>
  `,
  styles: `.kpi { font-size: 28px; font-weight: 700; } .kpis { margin-bottom: 16px; } h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; }
    .paused { background: #fff4e0; border: 1px solid #b26a00; padding: 8px 12px; border-radius: 8px; }`,
})
export class QueuesPage {
  private readonly ops = inject(OpsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly seconds = QUEUE_REFRESH_MS / 1000;
  readonly auto = signal(true);
  readonly stats = signal<QueueStats | null>(null);
  readonly error = signal<NormalizedError | null>(null);
  readonly updated = signal('');
  readonly age = computed(() => formatAge(this.stats()?.oldestMessageAgeSeconds));
  readonly depth = computed<ChartDatum[]>(() => {
    const d = this.stats()?.depthByPriority ?? {};
    const known = PRIORITIES.map((p) => ({ label: p, value: d[p] ?? 0 }));
    const extra = Object.keys(d).filter((k) => !PRIORITIES.includes(k as never)).map((k) => ({ label: k, value: d[k] }));
    return [...known, ...extra];
  });

  constructor() {
    // Immediate first load, then poll while auto-refresh is on. Stops automatically on destroy.
    timer(0, QUEUE_REFRESH_MS).pipe(
      filter((tick) => tick === 0 || this.auto()),
      switchMap(() => this.ops.queues(true).pipe(catchError((e: unknown) => { this.error.set(toNormalizedError(e)); return EMPTY; }))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((s) => this.apply(s));
  }

  private apply(s: QueueStats): void {
    this.stats.set(s);
    this.error.set(null);
    this.updated.set(new Date().toLocaleTimeString());
  }

  refresh(): void {
    this.ops.queues(true).subscribe({
      next: (s) => this.apply(s),
      error: (e: unknown) => this.error.set(toNormalizedError(e)),
    });
  }
}
