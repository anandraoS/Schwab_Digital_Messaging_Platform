import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { OpsService } from '../../core/api/ops.service';
import { MESSAGE_STATUSES, OpsSummary } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { BarChartComponent, ChartDatum, DonutChartComponent } from '../../shared/charts';
import { EmptyStateComponent } from '../../shared/empty-state';
import { ErrorBannerComponent } from '../../shared/error-banner';

@Component({
  selector: 'app-dashboard-page',
  imports: [MatButtonModule, BarChartComponent, DonutChartComponent, EmptyStateComponent, ErrorBannerComponent],
  template: `
    <div class="page">
      <div class="toolbar-row">
        <h1 class="page-title grow">Dashboard</h1>
        <button mat-stroked-button type="button" (click)="reload()">Refresh</button>
      </div>
      <app-error-banner [error]="state.error()" (retry)="reload()" />
      @if (state.loading() && !summary()) { <p role="status">Loading summary…</p> }
      @if (summary(); as s) {
        @if (s.total === 0) {
          <app-empty-state title="No messages in the last 24 hours" message="Submit a message to see activity here." />
        } @else {
          <div class="grid kpis">
            <section class="card" aria-label="Total messages">
              <div class="muted">Messages (last {{ s.windowHours ?? 24 }}h)</div><div class="kpi">{{ s.total }}</div>
            </section>
            <section class="card" aria-label="Success rate">
              <div class="muted">Success rate</div><div class="kpi">{{ successPct() }}%</div>
              <div class="meter" role="meter" aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="successPct()" aria-label="Success rate">
                <div class="fill" [style.width.%]="successPct()"></div>
              </div>
            </section>
            <section class="card" aria-label="p95 dispatch delay">
              <div class="muted">p95 dispatch delay</div><div class="kpi">{{ s.p95DispatchDelayMs ? s.p95DispatchDelayMs : '—' }}<small> ms</small></div>
            </section>
          </div>
          <div class="grid charts">
            <section class="card"><h2>By status</h2><app-bar-chart [data]="statusData()" title="Messages by status" /></section>
            <section class="card"><h2>By channel</h2><app-donut-chart [data]="channelData()" title="Messages by channel" /></section>
          </div>
        }
      }
    </div>
  `,
  styles: `
    .kpi { font-size: 32px; font-weight: 700; margin-top: 4px; } .kpi small { font-size: 14px; font-weight: 400; }
    .kpis { margin-bottom: 16px; } .charts { grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); }
    h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; }
    .meter { margin-top: 8px; height: 8px; background: #e0e0e0; border-radius: 4px; overflow: hidden; }
    .fill { height: 100%; background: #26a269; }
  `,
})
export class DashboardPage {
  private readonly ops = inject(OpsService);
  readonly state = new Loadable<OpsSummary>();
  readonly summary = this.state.data;
  readonly successPct = computed(() => Math.round((this.summary()?.successRate ?? 0) * 1000) / 10);
  readonly statusData = computed<ChartDatum[]>(() => {
    const by = this.summary()?.byStatus ?? {};
    const known = MESSAGE_STATUSES.filter((s) => (by[s] ?? 0) > 0).map((s) => ({ label: s, value: by[s] }));
    const extra = Object.keys(by).filter((k) => !MESSAGE_STATUSES.includes(k as never) && by[k] > 0).map((k) => ({ label: k, value: by[k] }));
    return [...known, ...extra];
  });
  readonly channelData = computed<ChartDatum[]>(() =>
    Object.entries(this.summary()?.byChannel ?? {}).map(([label, value]) => ({ label, value })),
  );

  constructor() {
    this.reload();
  }

  reload(): void {
    this.state.load(this.ops.summary());
  }
}
