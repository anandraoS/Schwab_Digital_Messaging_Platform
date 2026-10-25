import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { startWith } from 'rxjs';
import { MessagesService } from '../../core/api/messages.service';
import { TemplatesService } from '../../core/api/templates.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { newCorrelationId } from '../../core/http/interceptors';
import {
  CATEGORIES, CHANNELS, Channel, PRIORITIES, SubmitResult, TemplateSummary,
} from '../../core/models/api.models';
import {
  MAX_PUSH_TTL_SECONDS, emailValidator, expiryAfterSend, fallbackNotPrimary, futureDate,
  integerRange, localeValidator, oneOf, smsSenderIdValidator, uniqueKeys, variableKeyValidator,
} from '../../core/forms/validators';
import { ErrorBannerComponent } from '../../shared/error-banner';
import { StatusChipComponent } from '../../shared/status-chip';
import { SubmitFormValue, buildSubmitRequest } from './submit-request';

@Component({
  selector: 'app-message-submit-page',
  imports: [
    ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    ErrorBannerComponent, StatusChipComponent,
  ],
  template: `
    <div class="page">
      <h1 class="page-title">Submit a message</h1>
      <p class="muted">Message bodies are rendered server-side from templates; this console never shows or sends body text.</p>

      @if (result(); as r) {
        <div class="card ok" role="status">
          <strong>{{ r.replay ? 'Idempotent replay (HTTP ' + r.httpStatus + ')' : 'Accepted (HTTP ' + r.httpStatus + ')' }}</strong>
          <div>Message <a [routerLink]="['/messages', r.body.id]" class="mono">{{ r.body.id }}</a>
            <app-status-chip [status]="r.body.status" /> on {{ r.body.channel }}</div>
          <div class="mono">correlationId={{ r.body.correlationId }}</div>
        </div>
      }
      <app-error-banner [error]="error()" [retryable]="false" />

      <form [formGroup]="form" (ngSubmit)="submit()" novalidate aria-label="Submit message">
        <section class="card">
          <h2>Recipient</h2>
          <div class="form-grid">
            <mat-form-field><mat-label>Recipient id</mat-label><input matInput formControlName="recipientId" placeholder="rcp-001" /></mat-form-field>
            <mat-form-field><mat-label>or external reference</mat-label><input matInput formControlName="externalRef" placeholder="CUST-001" /></mat-form-field>
            <mat-form-field><mat-label>Client reference</mat-label><input matInput formControlName="clientReference" maxlength="100" /></mat-form-field>
          </div>
          @if (form.errors?.['oneOfRequired'] && form.touched) { <div class="field-err" role="alert">Provide a recipient id or an external reference.</div> }
        </section>

        <section class="card">
          <h2>Delivery</h2>
          <div class="form-grid">
            <mat-form-field><mat-label>Channel</mat-label>
              <mat-select formControlName="channel">@for (c of channels; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field><mat-label>Category</mat-label>
              <mat-select formControlName="category">@for (c of categories; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field><mat-label>Priority</mat-label>
              <mat-select formControlName="priority">@for (c of priorities; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field><mat-label>Fallback channels</mat-label>
              <mat-select formControlName="fallbackChannels" multiple>@for (c of fallbackOptions(); track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select>
              <mat-hint>Fallback never bypasses consent.</mat-hint></mat-form-field>
          </div>
          @if (form.errors?.['fallbackIsPrimary']) { <div class="field-err" role="alert">Fallback channels cannot include the primary channel.</div> }

          @switch (channel()) {
            @case ('EMAIL') {
              <div formGroupName="email" class="form-grid">
                <mat-form-field><mat-label>Reply-to</mat-label><input matInput formControlName="replyTo" type="email" />
                  @if (form.get('email.replyTo')?.hasError('email')) { <mat-error>Enter a valid e-mail address</mat-error> }</mat-form-field>
              </div>
            }
            @case ('SMS') {
              <div formGroupName="sms" class="form-grid">
                <mat-form-field><mat-label>Sender id</mat-label><input matInput formControlName="senderId" placeholder="ACME" />
                  <mat-hint>3-11 letters/digits</mat-hint>
                  @if (form.get('sms.senderId')?.hasError('senderId')) { <mat-error>Sender id must be 3-11 letters/digits</mat-error> }</mat-form-field>
              </div>
            }
            @case ('PUSH') {
              <div formGroupName="push" class="form-grid">
                <mat-form-field><mat-label>Collapse key</mat-label><input matInput formControlName="collapseKey" maxlength="64" /></mat-form-field>
                <mat-form-field><mat-label>TTL (seconds)</mat-label><input matInput formControlName="ttlSeconds" type="number" />
                  @if (form.get('push.ttlSeconds')?.hasError('range')) { <mat-error>TTL must be 1 to {{ maxTtl }}</mat-error> }</mat-form-field>
              </div>
            }
          }
        </section>

        <section class="card">
          <h2>Template</h2>
          <div class="form-grid">
            <mat-form-field><mat-label>Published template</mat-label>
              <mat-select [value]="form.controls.templateCode.value" (selectionChange)="pickTemplate($event.value)" aria-label="Template picker">
                @for (t of templates(); track t.id) { <mat-option [value]="t.code">{{ t.code }}</mat-option> }
              </mat-select>
              <mat-hint>{{ templates().length }} published for {{ channel() }}</mat-hint></mat-form-field>
            <mat-form-field><mat-label>Template code</mat-label><input matInput formControlName="templateCode" required />
              @if (form.get('templateCode')?.hasError('required')) { <mat-error>Template code is required</mat-error> }</mat-form-field>
            <mat-form-field><mat-label>Locale</mat-label><input matInput formControlName="locale" placeholder="en-US" required />
              @if (form.get('locale')?.invalid) { <mat-error>Use a locale like en or en-US</mat-error> }</mat-form-field>
            <mat-form-field><mat-label>Pinned version (optional)</mat-label><input matInput formControlName="templateVersion" type="number" />
              <mat-hint>Empty = latest PUBLISHED at accept time</mat-hint></mat-form-field>
          </div>
        </section>

        <section class="card">
          <h2>Variables</h2>
          <div formArrayName="variables">
            @for (row of variables.controls; track row; let i = $index) {
              <div [formGroupName]="i" class="var-row">
                <mat-form-field><mat-label>Key</mat-label><input matInput formControlName="key" />
                  @if (row.get('key')?.invalid) { <mat-error>Letters, digits, underscore; start with a letter</mat-error> }</mat-form-field>
                <mat-form-field><mat-label>Value</mat-label><input matInput formControlName="value" /></mat-form-field>
                <button mat-button type="button" (click)="removeVariable(i)" [attr.aria-label]="'Remove variable ' + (i + 1)">Remove</button>
              </div>
            }
          </div>
          @if (variables.errors?.['duplicateKeys']) { <div class="field-err" role="alert">Variable keys must be unique.</div> }
          <button mat-stroked-button type="button" (click)="addVariable()">Add variable</button>
        </section>

        <section class="card" formGroupName="schedule">
          <h2>Schedule (optional)</h2>
          <div class="form-grid">
            <mat-form-field><mat-label>Send at</mat-label><input matInput type="datetime-local" formControlName="sendAt" />
              @if (form.get('schedule.sendAt')?.hasError('pastDate')) { <mat-error>Send time must be in the future</mat-error> }</mat-form-field>
            <mat-form-field><mat-label>Expires at</mat-label><input matInput type="datetime-local" formControlName="expiresAt" /></mat-form-field>
          </div>
          @if (form.get('schedule')?.hasError('expiryBeforeSend')) { <div class="field-err" role="alert">Expiry must be after the send time.</div> }
        </section>

        <section class="card">
          <h2>Idempotency</h2>
          <div class="toolbar-row">
            <code class="mono" data-testid="idem-key">{{ idempotencyKey() }}</code>
            <button mat-button type="button" (click)="regenerateKey()">New key</button>
          </div>
          <p class="muted">Resubmitting with the same key returns the original response (HTTP 200, Idempotent-Replay). Use "New key" to send a different message.</p>
        </section>

        <div class="toolbar-row">
          <button mat-flat-button type="submit" [disabled]="submitting()">{{ submitting() ? 'Submitting…' : 'Submit message' }}</button>
          <button mat-button type="button" (click)="reset()">Reset</button>
        </div>
      </form>
    </div>
  `,
  styles: `
    section.card { margin-bottom: 16px; } h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; }
    .var-row { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .field-err { color: #b3261e; margin: 4px 0 8px; font-size: 13px; }
    .ok { border-color: #26a269; background: #eefaf2; margin-bottom: 12px; }
  `,
})
export class MessageSubmitPage {
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly messages = inject(MessagesService);
  private readonly templatesApi = inject(TemplatesService);
  private readonly destroyRef = inject(DestroyRef);

  readonly channels = CHANNELS;
  readonly categories = CATEGORIES;
  readonly priorities = PRIORITIES;
  readonly maxTtl = MAX_PUSH_TTL_SECONDS;

  readonly form = this.fb.group(
    {
      clientReference: [''],
      recipientId: [''],
      externalRef: [''],
      channel: ['EMAIL' as Channel, Validators.required],
      category: ['TRANSACTIONAL' as (typeof CATEGORIES)[number], Validators.required],
      priority: ['NORMAL' as (typeof PRIORITIES)[number], Validators.required],
      templateCode: ['', Validators.required],
      locale: ['en-US', [Validators.required, localeValidator]],
      templateVersion: [null as number | null, [integerRange(1, 100000)]],
      variables: new FormArray<FormGroup>([], uniqueKeys),
      email: this.fb.group({ replyTo: ['', emailValidator] }),
      sms: this.fb.group({ senderId: ['', smsSenderIdValidator] }),
      push: this.fb.group({ collapseKey: [''], ttlSeconds: [null as number | null, integerRange(1, MAX_PUSH_TTL_SECONDS)] }),
      fallbackChannels: [[] as Channel[]],
      schedule: this.fb.group({ sendAt: ['', futureDate()], expiresAt: [''] }, { validators: expiryAfterSend }),
    },
    { validators: [oneOf('recipientId', 'externalRef'), fallbackNotPrimary('channel', 'fallbackChannels')] },
  );

  readonly channel = toSignal(this.form.controls.channel.valueChanges.pipe(startWith(this.form.controls.channel.value)), {
    initialValue: 'EMAIL' as Channel,
  });
  readonly fallbackOptions = computed(() => CHANNELS.filter((c) => c !== this.channel()));
  readonly templates = signal<TemplateSummary[]>([]);
  readonly idempotencyKey = signal(newCorrelationId());
  readonly submitting = signal(false);
  readonly result = signal<SubmitResult | null>(null);
  readonly error = signal<NormalizedError | null>(null);

  constructor() {
    this.form.controls.channel.valueChanges.pipe(startWith(this.form.controls.channel.value), takeUntilDestroyed(this.destroyRef))
      .subscribe((c) => this.loadTemplates(c));
    // Never send a fallback equal to the new primary channel.
    this.form.controls.channel.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((c) => {
      const fb = this.form.controls.fallbackChannels;
      fb.setValue(fb.value.filter((x) => x !== c));
    });
  }

  get variables(): FormArray<FormGroup> {
    return this.form.controls.variables;
  }

  addVariable(key = '', value = ''): void {
    this.variables.push(this.fb.group({ key: [key, variableKeyValidator], value: [value] }));
  }

  removeVariable(i: number): void {
    this.variables.removeAt(i);
  }

  loadTemplates(channel: Channel): void {
    this.templatesApi.list({ channel, status: 'PUBLISHED', size: 200 }).subscribe({
      next: (p) => this.templates.set(p.content),
      error: () => this.templates.set([]), // picker is optional; the code can still be typed
    });
  }

  pickTemplate(code: string): void {
    this.form.controls.templateCode.setValue(code);
    const t = this.templates().find((x) => x.code === code);
    if (!t) {
      return;
    }
    this.templatesApi.get(t.id).subscribe({
      next: (d) => {
        const pub = d.versions.filter((v) => v.status === 'PUBLISHED');
        const first = pub[0];
        if (first) {
          this.form.controls.locale.setValue(first.locale);
          if (this.variables.length === 0) {
            first.variables.forEach((k) => this.addVariable(k, ''));
          }
        }
      },
      error: () => undefined,
    });
  }

  regenerateKey(): void {
    this.idempotencyKey.set(newCorrelationId());
    this.result.set(null);
    this.error.set(null);
  }

  reset(): void {
    this.form.reset();
    this.variables.clear();
    this.result.set(null);
    this.error.set(null);
    this.regenerateKey();
  }

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.result.set(null);
    const req = buildSubmitRequest(this.form.getRawValue() as unknown as SubmitFormValue);
    this.messages.submit(req, this.idempotencyKey()).subscribe({
      next: (r) => {
        this.result.set(r);
        this.submitting.set(false);
      },
      error: (e: unknown) => {
        this.error.set(toNormalizedError(e));
        this.submitting.set(false);
      },
    });
  }
}
