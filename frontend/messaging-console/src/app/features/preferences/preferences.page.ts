import { Component, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RecipientsService } from '../../core/api/recipients.service';
import { ACCESS } from '../../core/auth/access';
import { AuthService } from '../../core/auth/auth.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { Channel, PageResponse, RecipientPreferences, RecipientSummary, UpdatePreferencesRequest } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { Column, DataTableComponent } from '../../shared/data-table';
import { ErrorBannerComponent } from '../../shared/error-banner';

const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

/** HH:mm, or blank (= use the default quiet-hours window). */
export function timeValidator(c: AbstractControl): ValidationErrors | null {
  const v = String(c.value ?? '').trim();
  return v === '' || TIME_RE.test(v) ? null : { time: true };
}

/** Quiet hours need both ends or neither. */
export function quietPairValidator(g: AbstractControl): ValidationErrors | null {
  const s = String(g.get('quietStart')?.value ?? '').trim();
  const e = String(g.get('quietEnd')?.value ?? '').trim();
  return (s === '') === (e === '') ? null : { quietPair: true };
}

export function parseChannels(text: string): Channel[] {
  return text.split(',').map((p) => p.trim()).filter(Boolean) as Channel[];
}

export function fallbackOrderValidator(c: AbstractControl): ValidationErrors | null {
  const v = String(c.value ?? '').trim();
  if (!v) {
    return null;
  }
  const parts = v.split(',').map((p) => p.trim());
  const ok = parts.every((p) => ['EMAIL', 'SMS', 'PUSH'].includes(p)) && new Set(parts).size === parts.length;
  return ok ? null : { fallbackOrder: true };
}

@Component({
  selector: 'app-preferences-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, DataTableComponent, ErrorBannerComponent],
  template: `
    <div class="page">
      <h1 class="page-title">Recipient preferences</h1>
      <form class="toolbar-row" (ngSubmit)="search()" [formGroup]="searchForm" aria-label="Recipient search">
        <mat-form-field><mat-label>Search recipients</mat-label><input matInput formControlName="q" placeholder="name, reference or id" /></mat-form-field>
        <button mat-flat-button type="submit">Search</button>
      </form>
      <app-data-table caption="Recipients" [columns]="columns" [rows]="results.data()?.content ?? []" [loading]="results.loading()"
        [error]="results.error()" [rowActions]="pick" emptyTitle="No recipients" emptyMessage="Search to find a recipient." (retry)="search()" />
      <ng-template #pick let-row><button mat-stroked-button type="button" (click)="select(row)">Edit preferences</button></ng-template>

      @if (selected(); as r) {
        <section class="card prefs" aria-label="Preference editor">
          <h2>Preferences for <span class="mono">{{ r.externalRef }}</span> ({{ r.id }})</h2>
          <app-error-banner [error]="prefs.error()" (retry)="select(r)" />
          @if (conflict()) {
            <div class="conflict" role="alert">
              <strong>Edit conflict.</strong> {{ conflict()?.message }}
              <div class="toolbar-row">
                <button mat-stroked-button type="button" (click)="reload(false)">Reload latest (discard my edits)</button>
                <button mat-flat-button type="button" (click)="reload(true)">Reload latest and re-apply my edits</button>
              </div>
            </div>
          }
          <app-error-banner [error]="saveError()" [retryable]="false" />
          @if (prefs.loading()) { <p role="status">Loading preferences…</p> }
          @if (prefs.data()) {
            <form [formGroup]="form" (ngSubmit)="save()" aria-label="Preferences form" novalidate>
              <div class="checks">
                <mat-checkbox formControlName="marketingOptIn">Marketing opt-in</mat-checkbox>
                <mat-checkbox formControlName="emailEnabled">E-mail enabled</mat-checkbox>
                <mat-checkbox formControlName="smsEnabled">SMS enabled</mat-checkbox>
                <mat-checkbox formControlName="pushEnabled">Push enabled</mat-checkbox>
              </div>
              <div class="form-grid">
                <mat-form-field><mat-label>Quiet hours start (HH:mm)</mat-label><input matInput formControlName="quietStart" placeholder="default 21:00" />
                  @if (form.controls.quietStart.invalid) { <mat-error>Use 24h HH:mm or leave blank</mat-error> }</mat-form-field>
                <mat-form-field><mat-label>Quiet hours end (HH:mm)</mat-label><input matInput formControlName="quietEnd" placeholder="default 08:00" />
                  @if (form.controls.quietEnd.invalid) { <mat-error>Use 24h HH:mm or leave blank</mat-error> }</mat-form-field>
                @if (form.hasError('quietPair')) { <p class="muted" role="alert">Set both quiet-hours ends, or leave both blank for the default window.</p> }
                <mat-form-field><mat-label>Fallback order</mat-label><input matInput formControlName="fallbackOrder" placeholder="EMAIL,SMS,PUSH" />
                  @if (form.controls.fallbackOrder.invalid) { <mat-error>Comma-separated, unique: EMAIL, SMS, PUSH</mat-error> }</mat-form-field>
              </div>
              <p class="muted">Loaded version: {{ prefs.data()?.version }}. Saves are rejected if someone else changed the record first.</p>
              @if (canEdit()) {
                <button mat-flat-button type="submit" [disabled]="saving()">{{ saving() ? 'Saving…' : 'Save preferences' }}</button>
              } @else { <p class="muted">Your role can view preferences but not edit them.</p> }
              @if (saved()) { <span role="status" class="ok"> Saved.</span> }
            </form>
          }
        </section>
      }
    </div>
  `,
  styles: `
    h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; } .prefs { margin-top: 16px; }
    .checks { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 8px; }
    .conflict { border: 1px solid #b26a00; background: #fff4e0; color: #4a2c00; padding: 10px 14px; border-radius: 8px; margin: 8px 0; }
    .ok { color: #0b5a22; }
  `,
})
export class PreferencesPage {
  private readonly api = inject(RecipientsService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly searchForm = this.fb.group({ q: [''] });
  readonly results = new Loadable<PageResponse<RecipientSummary>>();
  readonly prefs = new Loadable<RecipientPreferences>();
  readonly selected = signal<RecipientSummary | null>(null);
  readonly saving = signal(false);
  readonly saved = signal(false);
  readonly saveError = signal<NormalizedError | null>(null);
  readonly conflict = signal<NormalizedError | null>(null);
  readonly canEdit = computed(() => this.auth.hasAnyRole(ACCESS.preferencesEdit));

  readonly form = this.fb.group({
    marketingOptIn: false, emailEnabled: true, smsEnabled: true, pushEnabled: true,
    quietStart: ['', [timeValidator]],
    quietEnd: ['', [timeValidator]],
    fallbackOrder: ['', fallbackOrderValidator],
  }, { validators: quietPairValidator });

  readonly columns: Column<RecipientSummary>[] = [
    { key: 'id', header: 'Id', type: 'mono' }, { key: 'externalRef', header: 'External ref' },
    { key: 'displayName', header: 'Name' }, { key: 'maskedAddress', header: 'Address', type: 'masked', value: (r) => r.maskedEmail ?? r.maskedPhone ?? '' }, { key: 'locale', header: 'Locale' },
  ];

  search(): void {
    this.results.load(this.api.search(this.searchForm.getRawValue().q.trim(), 0, 20));
  }

  select(r: RecipientSummary): void {
    this.selected.set(r);
    this.conflict.set(null);
    this.saveError.set(null);
    this.saved.set(false);
    this.prefs.load(this.api.getPreferences(r.id), (p) => this.patch(p));
  }

  private patch(p: RecipientPreferences): void {
    this.form.reset({
      marketingOptIn: p.marketingOptIn, emailEnabled: p.emailEnabled, smsEnabled: p.smsEnabled, pushEnabled: p.pushEnabled,
      quietStart: p.quietStart ?? '', quietEnd: p.quietEnd ?? '', fallbackOrder: (p.fallbackOrder ?? []).join(','),
    });
    if (!this.canEdit()) {
      this.form.disable(); // read-only roles (UX only; the API rejects writes anyway)
    }
  }

  /** Reloads after a 409. When `reapply` is true the user's unsaved edits are laid over the fresh values. */
  reload(reapply: boolean): void {
    const r = this.selected();
    if (!r) {
      return;
    }
    const mine = this.form.getRawValue();
    this.conflict.set(null);
    this.saveError.set(null);
    this.prefs.load(this.api.getPreferences(r.id), (p) => {
      this.patch(p);
      if (reapply) {
        this.form.patchValue(mine);
        this.form.markAsDirty();
      }
    });
  }

  save(): void {
    const r = this.selected();
    const loaded = this.prefs.data();
    this.form.markAllAsTouched();
    if (!r || !loaded || this.form.invalid) {
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.saveError.set(null);
    this.conflict.set(null);
    const v = this.form.getRawValue();
    const body: UpdatePreferencesRequest = {
      marketingOptIn: v.marketingOptIn, emailEnabled: v.emailEnabled, smsEnabled: v.smsEnabled, pushEnabled: v.pushEnabled,
      quietStart: v.quietStart.trim() || null, quietEnd: v.quietEnd.trim() || null,
      timeZone: loaded.timeZone ?? null, fallbackOrder: parseChannels(v.fallbackOrder), version: loaded.version,
    };
    this.api.updatePreferences(r.id, body).subscribe({
      next: (p) => {
        this.saving.set(false);
        this.saved.set(true);
        this.prefs.data.set(p);
        this.patch(p);
      },
      error: (e: unknown) => {
        this.saving.set(false);
        const n = toNormalizedError(e);
        if (n.code === 'CONFLICT_OPTIMISTIC_LOCK') {
          this.conflict.set(n);
        } else {
          this.saveError.set(n);
        }
      },
    });
  }
}
