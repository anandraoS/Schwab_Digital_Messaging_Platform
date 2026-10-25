import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink } from '@angular/router';
import { TemplateAction, TemplatesService } from '../../core/api/templates.service';
import { ACCESS } from '../../core/auth/access';
import { AuthService } from '../../core/auth/auth.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { AuditEvent, TemplateDetail, TemplateVersion } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { ConfirmService } from '../../shared/confirm-dialog';
import { Column, DataTableComponent } from '../../shared/data-table';
import { ErrorBannerComponent } from '../../shared/error-banner';
import { StatusChipComponent } from '../../shared/status-chip';
import { TemplateEditorComponent, TemplateEditorValue } from './template-editor';

type EditorState = { kind: 'new-version' } | { kind: 'edit'; version: TemplateVersion } | null;

/**
 * Which workflow buttons to show for a version. UX only: the server enforces roles,
 * the four-eyes rule (approver != author) and state transitions.
 */
export function versionActions(v: TemplateVersion, roles: readonly string[], subject: string): {
  edit: boolean; submit: boolean; approve: boolean; reject: boolean; retire: boolean; fourEyesBlocked: boolean;
} {
  const has = (allowed: readonly string[]): boolean => allowed.some((r) => roles.includes(r));
  const author = has(ACCESS.templateEdit);
  const approver = has(ACCESS.templateApprove);
  const blocked = !!v.createdBy && v.createdBy === subject;
  return {
    edit: author && v.status === 'DRAFT',
    submit: author && v.status === 'DRAFT',
    approve: approver && v.status === 'PENDING_APPROVAL',
    reject: approver && v.status === 'PENDING_APPROVAL',
    retire: has(ACCESS.templateRetire) && v.status === 'PUBLISHED',
    fourEyesBlocked: approver && v.status === 'PENDING_APPROVAL' && blocked,
  };
}

@Component({
  selector: 'app-template-detail-page',
  imports: [
    DatePipe, FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule, MatTabsModule,
    DataTableComponent, ErrorBannerComponent, StatusChipComponent, TemplateEditorComponent,
  ],
  template: `
    <div class="page">
      <div class="toolbar-row">
        <h1 class="page-title grow">Template <span class="mono">{{ t()?.code ?? id() }}</span></h1>
        <a mat-button routerLink="/templates">Back to list</a>
        @if (canAuthor() && !editor()) { <button mat-stroked-button type="button" (click)="editor.set({ kind: 'new-version' })">New version / locale</button> }
      </div>
      <app-error-banner [error]="state.error()" (retry)="reload()" />
      <app-error-banner [error]="actionError()" [retryable]="false" />
      @if (notice()) { <p role="status" class="notice">{{ notice() }}</p> }
      @if (state.loading() && !t()) { <p role="status">Loading template…</p> }

      @if (t(); as d) {
        <p>{{ d.channel }} · {{ d.category }}</p>
        @if (editor(); as ed) {
          <section class="card">
            <h2>{{ ed.kind === 'edit' ? 'Edit draft v' + ed.version.versionNo + ' (' + ed.version.locale + ')' : 'New draft version' }}</h2>
            <app-template-editor [mode]="ed.kind === 'edit' ? 'edit' : 'version'" [fixedChannel]="d.channel"
              [initial]="ed.kind === 'edit' ? ed.version : null" [busy]="saving()" [error]="editorError()"
              (saved)="save($event, ed)" (cancelled)="editor.set(null)" />
          </section>
        }
        @if (locales().length === 0) { <p class="muted">No versions yet.</p> }
        <mat-tab-group animationDuration="0ms" aria-label="Locales">
          @for (loc of locales(); track loc) {
            <mat-tab [label]="loc">
              @for (v of versionsFor(loc); track v.versionNo) {
                @let a = actions(v);
                <section class="card ver">
                  <div class="toolbar-row">
                    <strong>Version {{ v.versionNo }}</strong> <app-status-chip [status]="v.status" />
                    <span class="muted">author: {{ v.createdBy ?? '—' }} · approver: {{ v.approvedBy ?? '—' }} · published: {{ v.publishedAt ? (v.publishedAt | date: 'medium') : '—' }}</span>
                  </div>
                  @if (v.subject) { <div><strong>Subject:</strong> {{ v.subject }}</div> }
                  <pre class="src" aria-label="Template source">{{ v.body }}</pre>
                  <div class="muted">Variables: {{ v.variables.length ? v.variables.join(', ') : 'none' }}</div>
                  @if (v.rejectionComment) { <div class="muted">Review comment: {{ v.rejectionComment }}</div> }
                  <div class="toolbar-row actions">
                    @if (a.edit) { <button mat-stroked-button type="button" (click)="editor.set({ kind: 'edit', version: v })">Edit</button> }
                    @if (a.submit) { <button mat-flat-button type="button" (click)="act(v, 'submit')">Submit for approval</button> }
                    @if (a.approve) {
                      <button mat-flat-button type="button" [disabled]="a.fourEyesBlocked" (click)="act(v, 'approve')"
                              [attr.title]="a.fourEyesBlocked ? 'Four-eyes: you authored this version' : null">Approve</button>
                    }
                    @if (a.reject) {
                      <mat-form-field subscriptSizing="dynamic"><mat-label>Rejection comment</mat-label>
                        <input matInput [ngModel]="comment(v)" (ngModelChange)="setComment(v, $event)" [name]="'c' + key(v)" /></mat-form-field>
                      <button mat-stroked-button type="button" [disabled]="!comment(v).trim()" (click)="act(v, 'reject')">Reject</button>
                    }
                    @if (a.retire) { <button mat-stroked-button type="button" (click)="act(v, 'retire')">Retire</button> }
                    @if (a.fourEyesBlocked) { <span class="muted">You authored this version; another approver must approve it.</span> }
                  </div>
                </section>
              }
            </mat-tab>
          }
        </mat-tab-group>

        <section class="card hist"><h2>Change history</h2>
          <app-data-table caption="Template change history" [columns]="historyCols" [rows]="history.data() ?? []"
            [loading]="history.loading()" [error]="history.error()" emptyTitle="No history" emptyMessage="" (retry)="loadHistory()" />
        </section>
      }
    </div>
  `,
  styles: `
    h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; } .ver { margin: 12px 0; } .hist { margin-top: 16px; }
    .src { background: var(--mat-sys-surface-container); padding: 8px; border-radius: 6px; white-space: pre-wrap; word-break: break-word; }
    .actions { align-items: center; } .notice { color: #0b5a22; }
  `,
})
export class TemplateDetailPage {
  private readonly api = inject(TemplatesService);
  private readonly auth = inject(AuthService);
  private readonly confirmSvc = inject(ConfirmService);

  readonly id = input.required<string>();
  readonly state = new Loadable<TemplateDetail>();
  readonly history = new Loadable<AuditEvent[]>();
  readonly t = this.state.data;
  readonly editor = signal<EditorState>(null);
  readonly saving = signal(false);
  readonly editorError = signal<NormalizedError | null>(null);
  readonly actionError = signal<NormalizedError | null>(null);
  readonly notice = signal('');
  readonly comments = signal<Record<string, string>>({});

  readonly canAuthor = () => this.auth.hasAnyRole(ACCESS.templateAuthor);
  readonly locales = computed(() => Array.from(new Set((this.t()?.versions ?? []).map((v) => v.locale))));
  readonly historyCols: Column<AuditEvent>[] = [
    { key: 'occurredAt', header: 'When', type: 'date' }, { key: 'action', header: 'Action' }, { key: 'actor', header: 'Actor' },
    { key: 'detail', header: 'Detail', value: (e) => (typeof e.detail === 'string' ? e.detail : e.detail ? JSON.stringify(e.detail) : '') },
  ];

  constructor() {
    effect(() => {
      this.id();
      this.reload();
    });
  }

  key(v: TemplateVersion): string {
    return `${v.locale}#${v.versionNo}`;
  }

  comment(v: TemplateVersion): string {
    return (this.comments() as Partial<Record<string, string>>)[this.key(v)] ?? '';
  }

  setComment(v: TemplateVersion, text: string): void {
    this.comments.update((c) => ({ ...c, [this.key(v)]: text }));
  }

  versionsFor(locale: string): TemplateVersion[] {
    return (this.t()?.versions ?? []).filter((v) => v.locale === locale).sort((a, b) => b.versionNo - a.versionNo);
  }

  actions(v: TemplateVersion): ReturnType<typeof versionActions> {
    return versionActions(v, this.auth.roles(), this.auth.subject());
  }

  reload(): void {
    this.state.load(this.api.get(this.id()));
    this.loadHistory();
  }

  loadHistory(): void {
    this.history.load(this.api.history(this.id()));
  }

  save(v: TemplateEditorValue, ed: NonNullable<EditorState>): void {
    this.saving.set(true);
    this.editorError.set(null);
    const body = { locale: v.locale, subject: v.subject || undefined, body: v.body, variables: v.variables, version: v.version };
    const call = ed.kind === 'edit'
      ? this.api.updateVersion(this.id(), ed.version.versionNo, ed.version.locale, body)
      : this.api.addVersion(this.id(), body);
    call.subscribe({
      next: () => { this.saving.set(false); this.editor.set(null); this.notice.set('Saved.'); this.reload(); },
      error: (e: unknown) => { this.saving.set(false); this.editorError.set(toNormalizedError(e)); },
    });
  }

  act(v: TemplateVersion, action: TemplateAction): void {
    const run = (): void => {
      this.actionError.set(null);
      this.notice.set('');
      const comment = action === 'reject' ? this.comment(v).trim() : undefined;
      this.api.act(this.id(), v.versionNo, v.locale, action, comment).subscribe({
        next: () => { this.notice.set(`Version ${v.versionNo} (${v.locale}): ${action} succeeded.`); this.reload(); },
        error: (e: unknown) => this.actionError.set(toNormalizedError(e)),
      });
    };
    if (action === 'approve' || action === 'retire') {
      const text = action === 'approve'
        ? 'Publishing makes this version live and retires the previously published version of this locale.'
        : 'Retired versions can no longer be used for new messages.';
      this.confirmSvc.confirm({ title: `${action === 'approve' ? 'Approve' : 'Retire'} version ${v.versionNo}?`, message: text,
        confirmLabel: action === 'approve' ? 'Approve' : 'Retire', danger: action === 'retire' }).subscribe((ok) => ok && run());
    } else {
      run();
    }
  }
}
