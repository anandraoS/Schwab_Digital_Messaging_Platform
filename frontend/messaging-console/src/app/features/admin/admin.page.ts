import { Component, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { OpsService } from '../../core/api/ops.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { integerRange } from '../../core/forms/validators';
import { ProviderHealth } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { ConfirmService } from '../../shared/confirm-dialog';
import { ErrorBannerComponent } from '../../shared/error-banner';

@Component({
  selector: 'app-admin-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, ErrorBannerComponent],
  template: `
    <div class="page">
      <h1 class="page-title">Administration</h1>
      <p class="muted">Platform-admin operations. This page is hidden for other roles as a UX convenience; the API enforces PLATFORM_ADMIN.</p>
      <app-error-banner [error]="actionError()" [retryable]="false" />
      @if (notice()) { <p role="status" class="ok">{{ notice() }}</p> }

      <section class="card">
        <h2>Consumers</h2>
        <p class="muted">Pausing stops dispatch consumers from taking new work; accepted messages stay queued.</p>
        <div class="toolbar-row">
          <button mat-flat-button type="button" (click)="consumers(true)">Pause consumers</button>
          <button mat-stroked-button type="button" (click)="consumers(false)">Resume consumers</button>
        </div>
      </section>

      <section class="card cfg">
        <h2>Provider configuration</h2>
        <app-error-banner [error]="providers.error()" (retry)="loadProviders()" />
        @if (providers.loading()) { <p role="status">Loading providers…</p> }
        <form [formGroup]="form" aria-label="Provider configuration">
          <div formArrayName="rows">
            @for (g of rows.controls; track $index; let i = $index) {
              <div [formGroupName]="i" class="row">
                <strong class="pid mono">{{ g.get('providerId')?.value }}</strong>
                <mat-checkbox formControlName="enabled">Enabled</mat-checkbox>
                <mat-form-field subscriptSizing="dynamic"><mat-label>Rate limit /s</mat-label><input matInput type="number" formControlName="rateLimitPerSec" />
                  @if (g.get('rateLimitPerSec')?.invalid) { <mat-error>1 to 10000</mat-error> }</mat-form-field>
                <mat-form-field subscriptSizing="dynamic"><mat-label>Timeout ms</mat-label><input matInput type="number" formControlName="timeoutMs" />
                  @if (g.get('timeoutMs')?.invalid) { <mat-error>100 to 120000</mat-error> }</mat-form-field>
                <button mat-flat-button type="button" [disabled]="g.invalid" (click)="saveRow(i)">Save</button>
              </div>
            }
          </div>
        </form>
      </section>
    </div>
  `,
  styles: `h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; } section { margin-bottom: 16px; } .ok { color: #0b5a22; }
    .row { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; } .pid { min-width: 160px; }`,
})
export class AdminPage {
  private readonly ops = inject(OpsService);
  private readonly confirmSvc = inject(ConfirmService);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly providers = new Loadable<ProviderHealth[]>();
  readonly form = this.fb.group({ rows: new FormArray<FormGroup>([]) });
  readonly actionError = signal<NormalizedError | null>(null);
  readonly notice = signal('');

  constructor() {
    this.loadProviders();
  }

  get rows(): FormArray<FormGroup> {
    return this.form.controls.rows;
  }

  loadProviders(): void {
    this.providers.load(this.ops.providers(), (list) => {
      this.rows.clear();
      for (const p of list) {
        this.rows.push(this.fb.group({
          providerId: [p.providerId], enabled: [p.enabled],
          rateLimitPerSec: [p.rateLimitPerSec, [Validators.required, integerRange(1, 10000)]],
          timeoutMs: [p.timeoutMs ?? 2000, [Validators.required, integerRange(100, 120000)]],
        }));
      }
    });
  }

  saveRow(i: number): void {
    const g = this.rows.at(i);
    if (g.invalid) {
      return;
    }
    const v = g.getRawValue() as { providerId: string; enabled: boolean; rateLimitPerSec: number; timeoutMs: number };
    this.actionError.set(null);
    this.ops.updateProviderConfig(v.providerId, { enabled: v.enabled, rateLimitPerSec: Number(v.rateLimitPerSec), timeoutMs: Number(v.timeoutMs) }).subscribe({
      next: () => this.notice.set(`Provider ${v.providerId} configuration saved.`),
      error: (e: unknown) => this.actionError.set(toNormalizedError(e)),
    });
  }

  consumers(pause: boolean): void {
    this.actionError.set(null);
    this.notice.set('');
    this.confirmSvc.confirm({
      title: pause ? 'Pause consumers?' : 'Resume consumers?',
      message: pause ? 'No new messages will be dispatched until consumers are resumed.' : 'Dispatch consumers will start taking work again.',
      confirmLabel: pause ? 'Pause' : 'Resume', danger: pause,
    }).subscribe((ok) => {
      if (!ok) { return; }
      (pause ? this.ops.pauseConsumers() : this.ops.resumeConsumers()).subscribe({
        next: () => this.notice.set(pause ? 'Consumers paused.' : 'Consumers resumed.'),
        error: (e: unknown) => this.actionError.set(toNormalizedError(e)),
      });
    });
  }
}
