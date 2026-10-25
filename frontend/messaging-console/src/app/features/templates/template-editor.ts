import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { startWith } from 'rxjs';
import { TemplatesService } from '../../core/api/templates.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { localeValidator, templateCodeValidator } from '../../core/forms/validators';
import { CATEGORIES, CHANNELS, Category, Channel, TemplatePreviewResponse, TemplateVersion } from '../../core/models/api.models';
import { ErrorBannerComponent } from '../../shared/error-banner';

export interface TemplateEditorValue {
  code: string;
  name: string;
  channel: Channel;
  category: Category;
  locale: string;
  subject: string;
  body: string;
  variables: string[];
  version?: number;
}

/** Parses "a, b\nc" into a unique list of variable names. */
export function parseVariableList(text: string): string[] {
  return Array.from(new Set(text.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean)));
}

/** Parses `key=value` lines into a record. Lines without '=' are ignored. */
export function parseSampleData(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.split(/\r?\n/)) {
    const i = line.indexOf('=');
    if (i > 0) {
      out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
  }
  return out;
}

/** Template placeholders used in a body: {{name}}. */
export function placeholdersIn(body: string): string[] {
  return Array.from(new Set(Array.from(body.matchAll(/\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}/g)).map((m) => m[1])));
}

@Component({
  selector: 'app-template-editor',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, ErrorBannerComponent],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" class="editor" aria-label="Template editor" novalidate>
      <div class="form-grid">
        @if (mode() === 'create') {
          <mat-form-field><mat-label>Code</mat-label><input matInput formControlName="code" placeholder="TRADE_CONFIRMATION" />
            @if (form.controls.code.invalid) { <mat-error>Upper-case letters, digits and underscores (3-64)</mat-error> }</mat-form-field>
          <mat-form-field><mat-label>Name</mat-label><input matInput formControlName="name" placeholder="Trade confirmation" />
            @if (form.controls.name.invalid && form.controls.name.touched) { <mat-error>Name is required</mat-error> }</mat-form-field>
          <mat-form-field><mat-label>Channel</mat-label>
            <mat-select formControlName="channel">@for (c of channels; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
          <mat-form-field><mat-label>Category</mat-label>
            <mat-select formControlName="category">@for (c of categories; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
        }
        <mat-form-field><mat-label>Locale</mat-label><input matInput formControlName="locale" placeholder="en-US" />
          @if (form.controls.locale.invalid) { <mat-error>Use a locale like en or en-US</mat-error> }</mat-form-field>
        @if (showSubject()) {
          <mat-form-field><mat-label>Subject</mat-label><input matInput formControlName="subject" /></mat-form-field>
        }
      </div>
      <mat-form-field class="full"><mat-label>Body template (double-brace placeholders)</mat-label>
        <textarea matInput rows="6" formControlName="body"></textarea>
        @if (form.controls.body.invalid && form.controls.body.touched) { <mat-error>Body is required</mat-error> }</mat-form-field>
      <mat-form-field class="full"><mat-label>Allowed variables (comma or space separated)</mat-label>
        <input matInput formControlName="variables" />
        @if (undeclared().length) { <mat-hint>Undeclared placeholders in body: {{ undeclared().join(', ') }}</mat-hint> }</mat-form-field>

      <details class="preview">
        <summary>Preview with sample data</summary>
        <mat-form-field class="full"><mat-label>Sample data (key=value per line)</mat-label>
          <textarea matInput rows="3" formControlName="sampleData"></textarea></mat-form-field>
        <button mat-stroked-button type="button" (click)="preview()" [disabled]="previewing()">Render preview</button>
        <app-error-banner [error]="previewError()" [retryable]="false" />
        @if (previewResult(); as p) {
          <div class="card" role="status">
            @if (p.errors?.length) { <ul>@for (e of p.errors; track $index) { <li>{{ e.field }}: {{ e.message }}</li> }</ul> }
            @if (p.subject) { <div><strong>Subject:</strong> {{ p.subject }}</div> }
            @if (p.body) { <pre class="out">{{ p.body }}</pre> }
          </div>
        }
      </details>

      <app-error-banner [error]="error()" [retryable]="false" />
      <div class="toolbar-row">
        <button mat-flat-button type="submit" [disabled]="busy()">{{ submitLabel() }}</button>
        <button mat-button type="button" (click)="cancelled.emit()">Cancel</button>
      </div>
    </form>
  `,
  styles: `.full { width: 100%; } .out { white-space: pre-wrap; word-break: break-word; margin: 6px 0 0; }
    .preview { margin: 8px 0 12px; }`,
})
export class TemplateEditorComponent implements OnInit {
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly api = inject(TemplatesService);

  readonly mode = input<'create' | 'version' | 'edit'>('create');
  readonly fixedChannel = input<Channel | null>(null);
  readonly initial = input<TemplateVersion | null>(null);
  readonly busy = input(false);
  readonly error = input<NormalizedError | null>(null);
  readonly saved = output<TemplateEditorValue>();
  readonly cancelled = output<void>();

  readonly channels = CHANNELS;
  readonly categories = CATEGORIES;
  readonly previewing = signal(false);
  readonly previewResult = signal<TemplatePreviewResponse | null>(null);
  readonly previewError = signal<NormalizedError | null>(null);

  readonly form = this.fb.group({
    code: ['', [Validators.required, templateCodeValidator]],
    name: ['', [Validators.maxLength(200)]],
    channel: ['EMAIL' as Channel],
    category: ['TRANSACTIONAL' as Category],
    locale: ['en-US', [Validators.required, localeValidator]],
    subject: [''],
    body: ['', [Validators.required, Validators.maxLength(10000)]],
    variables: [''],
    sampleData: [''],
  });

  private readonly value = toSignal(this.form.valueChanges.pipe(startWith(this.form.getRawValue())), { initialValue: this.form.getRawValue() });
  readonly undeclared = computed(() => {
    const v = this.value();
    const declared = parseVariableList(v.variables ?? '');
    return placeholdersIn(v.body ?? '').filter((p) => !declared.includes(p));
  });
  readonly channelNow = computed(() => this.fixedChannel() ?? (this.value().channel as Channel));
  readonly showSubject = computed(() => this.channelNow() === 'EMAIL');
  readonly submitLabel = computed(() => (this.mode() === 'create' ? 'Create template' : this.mode() === 'edit' ? 'Save draft' : 'Create draft version'));

  ngOnInit(): void {
    const i = this.initial();
    if (this.mode() !== 'create') {
      this.form.controls.code.disable();
      this.form.controls.name.disable();
    } else {
      this.form.controls.name.addValidators(Validators.required);
      this.form.controls.name.updateValueAndValidity();
    }
    if (i) {
      this.form.patchValue({ locale: i.locale, subject: i.subject ?? '', body: i.body, variables: i.variables.join(', ') });
      if (this.mode() === 'edit') {
        this.form.controls.locale.disable();
      }
    }
  }

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    const v = this.form.getRawValue();
    this.saved.emit({
      code: v.code,
      name: v.name,
      channel: this.fixedChannel() ?? v.channel,
      category: v.category,
      locale: v.locale,
      subject: this.showSubject() ? v.subject : '',
      body: v.body,
      variables: parseVariableList(v.variables),
      version: this.initial()?.version,
    });
  }

  preview(): void {
    const v = this.form.getRawValue();
    this.previewing.set(true);
    this.previewError.set(null);
    this.api.preview({
      channel: this.channelNow(), subject: v.subject || undefined, body: v.body,
      variables: parseVariableList(v.variables), sampleData: parseSampleData(v.sampleData), locale: v.locale,
    }).subscribe({
      next: (r) => { this.previewResult.set(r); this.previewing.set(false); },
      error: (e: unknown) => { this.previewError.set(toNormalizedError(e)); this.previewing.set(false); },
    });
  }
}
