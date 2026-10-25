import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { OpsService } from '../../core/api/ops.service';
import { ACCESS } from '../../core/auth/access';
import { AuthService } from '../../core/auth/auth.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { FAULT_MODES, FaultMode, ProviderHealth } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { EmptyStateComponent } from '../../shared/empty-state';
import { ErrorBannerComponent } from '../../shared/error-banner';
import { StatusChipComponent } from '../../shared/status-chip';

export function pct(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : `${Math.round(v * 1000) / 10}%`;
}

@Component({
  selector: 'app-providers-page',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, EmptyStateComponent, ErrorBannerComponent, StatusChipComponent],
  template: `
    <div class="page">
      <div class="toolbar-row"><h1 class="page-title grow">Providers</h1><button mat-stroked-button type="button" (click)="reload()">Refresh</button></div>
      <p class="muted">All providers are simulators in this repository; no real provider is ever called.</p>
      <app-error-banner [error]="state.error()" (retry)="reload()" />
      <app-error-banner [error]="actionError()" [retryable]="false" />
      @if (notice()) { <p role="status" class="ok">{{ notice() }}</p> }
      @if (state.loading() && !state.data()) { <p role="status">Loading providers…</p> }
      @if (state.data(); as list) {
        @if (list.length === 0) { <app-empty-state title="No providers configured" /> }
        <div class="grid">
          @for (p of list; track p.providerId) {
            <section class="card" [attr.aria-label]="'Provider ' + p.providerId">
              <div class="toolbar-row"><strong class="grow">{{ p.providerId }}</strong> <app-status-chip [status]="p.circuitState" /></div>
              <dl>
                <div><dt>Channel</dt><dd>{{ p.channel }}</dd></div>
                <div><dt>Enabled</dt><dd><app-status-chip [status]="p.enabled ? 'ENABLED' : 'DISABLED'" /></dd></div>
                <div><dt>Success rate</dt><dd>{{ pct(p.successRate) }}</dd></div>
                <div><dt>p95 latency</dt><dd>{{ p.p95LatencyMs > 0 ? p.p95LatencyMs + ' ms' : '— (no calls in the last minutes)' }}</dd></div>
                <div><dt>Rate limit</dt><dd>{{ p.rateLimitPerSec }}/s</dd></div>
                <div><dt>Fault mode</dt><dd>{{ p.faultMode ?? 'n/a' }}</dd></div>
                <div><dt>Last error</dt><dd>{{ p.lastError ?? '—' }}</dd></div>
              </dl>
              @if (isAdmin()) {
                <fieldset class="fault"><legend>Fault injection (simulator only)</legend>
                  <mat-form-field subscriptSizing="dynamic"><mat-label>Mode</mat-label>
                    <mat-select [ngModel]="modeFor(p)" (ngModelChange)="setMode(p.providerId, $event)" [name]="'m' + p.providerId">
                      @for (m of modes; track m) { <mat-option [value]="m">{{ m }}</mat-option> }</mat-select></mat-form-field>
                  <mat-form-field subscriptSizing="dynamic"><mat-label>Probability (0-1)</mat-label>
                    <input matInput type="number" step="0.1" min="0" max="1" [ngModel]="probFor(p)" (ngModelChange)="setProb(p.providerId, $event)" [name]="'p' + p.providerId" /></mat-form-field>
                  <button mat-flat-button type="button" (click)="applyFault(p)">Apply</button>
                </fieldset>
              }
            </section>
          }
        </div>
      }
    </div>
  `,
  styles: `dl { margin: 8px 0; } dl > div { display: flex; gap: 8px; } dt { min-width: 100px; font-weight: 600; } dd { margin: 0; }
    .fault { border: 1px dashed var(--mat-sys-outline); border-radius: 8px; display: flex; flex-direction: column; gap: 8px; } .ok { color: #0b5a22; }`,
})
export class ProvidersPage {
  private readonly ops = inject(OpsService);
  private readonly auth = inject(AuthService);
  readonly modes = FAULT_MODES;
  readonly pct = pct;
  readonly state = new Loadable<ProviderHealth[]>();
  readonly isAdmin = computed(() => this.auth.hasAnyRole(ACCESS.platformAdmin));
  readonly actionError = signal<NormalizedError | null>(null);
  readonly notice = signal('');
  private readonly modeEdits = signal<Record<string, FaultMode>>({});
  private readonly probEdits = signal<Record<string, number>>({});

  constructor() {
    this.reload();
  }

  reload(): void {
    this.state.load(this.ops.providers());
  }

  modeFor(p: ProviderHealth): FaultMode {
    return this.modeEdits()[p.providerId] ?? ((p.faultMode as FaultMode | undefined) ?? 'NONE');
  }

  probFor(p: ProviderHealth): number {
    return this.probEdits()[p.providerId] ?? p.faultProbability ?? 0;
  }

  setMode(id: string, m: FaultMode): void {
    this.modeEdits.update((s) => ({ ...s, [id]: m }));
  }

  setProb(id: string, v: number): void {
    this.probEdits.update((s) => ({ ...s, [id]: Number(v) }));
  }

  applyFault(p: ProviderHealth): void {
    const probability = Math.min(1, Math.max(0, this.probFor(p)));
    this.actionError.set(null);
    this.ops.setFaultMode(p.providerId, { mode: this.modeFor(p), probability }).subscribe({
      next: () => { this.notice.set(`Fault mode for ${p.providerId} set to ${this.modeFor(p)} (p=${probability}).`); this.reload(); },
      error: (e: unknown) => this.actionError.set(toNormalizedError(e)),
    });
  }
}
