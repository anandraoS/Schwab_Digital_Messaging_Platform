import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { BulkJobsService } from '../../core/api/bulk-jobs.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { integerRange, templateCodeValidator } from '../../core/forms/validators';
import { BulkJob, CATEGORIES, CHANNELS, PageResponse } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { Column, DataTableComponent } from '../../shared/data-table';
import { ErrorBannerComponent } from '../../shared/error-banner';
import { PageChange } from '../../shared/pager';

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

/** Client-side CSV file pre-check (the server re-validates everything). */
export function validateCsvFile(file: File | null): string | null {
  if (!file) {
    return 'Choose a CSV file.';
  }
  if (!/\.csv$/i.test(file.name)) {
    return 'File must have a .csv extension.';
  }
  if (file.size === 0) {
    return 'File is empty.';
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return 'File is larger than 10 MB.';
  }
  return null;
}

@Component({
  selector: 'app-bulk-job-list-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, DataTableComponent, ErrorBannerComponent],
  template: `
    <div class="page">
      <div class="toolbar-row"><h1 class="page-title grow">Bulk jobs</h1><button mat-stroked-button type="button" (click)="reload()">Refresh</button></div>
      <app-error-banner [error]="createError()" [retryable]="false" />
      <div class="grid forms">
        <section class="card">
          <h2>Upload CSV</h2>
          <p class="muted">Header: <code>externalRef,locale,var_firstName,var_amount,…</code>. Max 10 MB here; the server enforces its own row limit.</p>
          <form [formGroup]="uploadForm" (ngSubmit)="upload()" novalidate aria-label="Upload bulk job">
            <mat-form-field><mat-label>Job name</mat-label><input matInput formControlName="name" /></mat-form-field>
            <mat-form-field><mat-label>Template code</mat-label><input matInput formControlName="templateCode" />
              @if (uploadForm.controls.templateCode.invalid) { <mat-error>Upper-case code, e.g. TRADE_CONFIRMATION</mat-error> }</mat-form-field>
            <mat-form-field><mat-label>Channel</mat-label>
              <mat-select formControlName="channel">@for (c of channels; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field><mat-label>Category</mat-label>
              <mat-select formControlName="category">@for (c of categories; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field><mat-label>Rate per second</mat-label><input matInput type="number" formControlName="ratePerSecond" />
              @if (uploadForm.controls.ratePerSecond.invalid) { <mat-error>1 to 1000</mat-error> }</mat-form-field>
            <div><label for="csv">CSV file</label> <input id="csv" type="file" accept=".csv,text/csv" (change)="onFile($event)" /></div>
            @if (fileError()) { <div class="err" role="alert">{{ fileError() }}</div> }
            <button mat-flat-button type="submit" [disabled]="busy()">Upload and start</button>
          </form>
        </section>
        <section class="card">
          <h2>Generate synthetic job</h2>
          <p class="muted">Creates and submits a synthetic data set server-side. No upload.</p>
          <form [formGroup]="synthForm" (ngSubmit)="generate()" novalidate aria-label="Generate synthetic job">
            <mat-form-field><mat-label>Rows</mat-label><input matInput type="number" formControlName="rows" />
              @if (synthForm.controls.rows.invalid) { <mat-error>1 to 1,000,000</mat-error> }</mat-form-field>
            <mat-form-field><mat-label>Duplicate rate (0-1)</mat-label><input matInput type="number" step="0.01" formControlName="duplicateRate" />
              @if (synthForm.controls.duplicateRate.invalid) { <mat-error>0 to 1</mat-error> }</mat-form-field>
            <mat-form-field><mat-label>Invalid rate (0-1)</mat-label><input matInput type="number" step="0.01" formControlName="invalidRate" />
              @if (synthForm.controls.invalidRate.invalid) { <mat-error>0 to 1</mat-error> }</mat-form-field>
            <button mat-flat-button type="submit" [disabled]="busy()">Generate</button>
          </form>
        </section>
      </div>
      <h2>Jobs</h2>
      <app-data-table caption="Bulk jobs" [columns]="columns" [rows]="state.data()?.content ?? []" [loading]="state.loading()"
        [error]="state.error()" [total]="state.data()?.totalElements ?? 0" [page]="page()" [size]="size()"
        emptyTitle="No bulk jobs" emptyMessage="Upload a CSV or generate a synthetic job." (pageChange)="onPage($event)" (retry)="reload()" />
    </div>
  `,
  styles: `h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; } .forms { grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); margin-bottom: 16px; }
    form { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; } .err { color: #b3261e; }`,
})
export class BulkJobListPage {
  private readonly api = inject(BulkJobsService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly channels = CHANNELS;
  readonly categories = CATEGORIES;
  readonly uploadForm = this.fb.group({
    name: ['', Validators.required],
    templateCode: ['', [Validators.required, templateCodeValidator]],
    channel: ['EMAIL' as (typeof CHANNELS)[number]],
    category: ['MARKETING' as (typeof CATEGORIES)[number]],
    ratePerSecond: [20, [Validators.required, integerRange(1, 1000)]],
  });
  readonly synthForm = this.fb.group({
    rows: [1000, [Validators.required, integerRange(1, 1_000_000)]],
    duplicateRate: [0.02, [Validators.required, Validators.min(0), Validators.max(1)]],
    invalidRate: [0.02, [Validators.required, Validators.min(0), Validators.max(1)]],
  });
  readonly state = new Loadable<PageResponse<BulkJob>>();
  readonly page = signal(0);
  readonly size = signal(20);
  readonly busy = signal(false);
  readonly createError = signal<NormalizedError | null>(null);
  readonly fileError = signal('');
  private file: File | null = null;

  readonly columns: Column<BulkJob>[] = [
    { key: 'name', header: 'Name', link: (r) => ['/bulk-jobs', r.id] },
    { key: 'status', header: 'Status', type: 'status' },
    { key: 'processed', header: 'Processed', value: (r) => `${r.processed} / ${r.total}` },
    { key: 'failed', header: 'Failed' }, { key: 'duplicate', header: 'Duplicates' },
    { key: 'startedAt', header: 'Started', type: 'date' },
  ];

  constructor() {
    this.reload();
  }

  reload(): void {
    this.state.load(this.api.list({ page: this.page(), size: this.size(), sort: 'startedAt,desc' }));
  }

  onPage(e: PageChange): void {
    this.page.set(e.page);
    this.size.set(e.size);
    this.reload();
  }

  onFile(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    this.file = input.files?.[0] ?? null;
    this.fileError.set(validateCsvFile(this.file) ?? '');
  }

  upload(): void {
    this.uploadForm.markAllAsTouched();
    const err = validateCsvFile(this.file);
    this.fileError.set(err ?? '');
    if (err || this.uploadForm.invalid || !this.file) {
      return;
    }
    this.run(this.api.upload(this.file, this.uploadForm.getRawValue()));
  }

  generate(): void {
    this.synthForm.markAllAsTouched();
    if (this.synthForm.invalid) {
      return;
    }
    this.run(this.api.generateSynthetic(this.synthForm.getRawValue()));
  }

  private run(call: ReturnType<BulkJobsService['generateSynthetic']>): void {
    this.busy.set(true);
    this.createError.set(null);
    call.subscribe({
      next: (job) => { this.busy.set(false); void this.router.navigate(['/bulk-jobs', job.id]); },
      error: (e: unknown) => { this.busy.set(false); this.createError.set(toNormalizedError(e)); },
    });
  }
}
