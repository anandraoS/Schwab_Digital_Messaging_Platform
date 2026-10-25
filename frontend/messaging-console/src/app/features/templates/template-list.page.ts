import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { TemplatesService } from '../../core/api/templates.service';
import { ACCESS } from '../../core/auth/access';
import { AuthService } from '../../core/auth/auth.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { CHANNELS, PageResponse, TemplateSummary } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { Column, DataTableComponent } from '../../shared/data-table';
import { PageChange } from '../../shared/pager';
import { TemplateEditorComponent, TemplateEditorValue } from './template-editor';

@Component({
  selector: 'app-template-list-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, DataTableComponent, TemplateEditorComponent],
  template: `
    <div class="page">
      <div class="toolbar-row">
        <h1 class="page-title grow">Templates</h1>
        @if (canCreate() && !creating()) { <button mat-flat-button type="button" (click)="creating.set(true)">New template</button> }
      </div>
      @if (creating()) {
        <section class="card create">
          <h2>New template</h2>
          <app-template-editor mode="create" [busy]="saving()" [error]="createError()" (saved)="create($event)" (cancelled)="creating.set(false)" />
        </section>
      }
      <form class="filters" [formGroup]="form" (ngSubmit)="apply()" aria-label="Template filters">
        <mat-form-field><mat-label>Channel</mat-label>
          <mat-select formControlName="channel"><mat-option value="">Any</mat-option>
            @for (c of channels; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Status</mat-label>
          <mat-select formControlName="status"><mat-option value="">Any</mat-option>
            @for (s of statuses; track s) { <mat-option [value]="s">{{ s }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Code contains</mat-label><input matInput formControlName="code" /></mat-form-field>
        <button mat-flat-button type="submit">Apply</button>
      </form>
      <app-data-table caption="Templates" [columns]="columns" [rows]="state.data()?.content ?? []" [loading]="state.loading()"
        [error]="state.error()" [total]="state.data()?.totalElements ?? 0" [page]="page()" [size]="size()"
        emptyTitle="No templates" emptyMessage="No template matches the filters." (pageChange)="onPage($event)" (retry)="reload()" />
    </div>
  `,
  styles: `h2 { font: var(--mat-sys-title-medium); margin: 0 0 8px; } .create { margin-bottom: 16px; }`,
})
export class TemplateListPage {
  private readonly api = inject(TemplatesService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly channels = CHANNELS;
  readonly statuses = ['DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'RETIRED'];
  readonly form = this.fb.group({ channel: [''], status: [''], code: [''] });
  readonly state = new Loadable<PageResponse<TemplateSummary>>();
  readonly page = signal(0);
  readonly size = signal(20);
  readonly creating = signal(false);
  readonly saving = signal(false);
  readonly createError = signal<NormalizedError | null>(null);
  readonly canCreate = () => this.auth.hasAnyRole(ACCESS.templateAuthor);

  readonly columns: Column<TemplateSummary>[] = [
    { key: 'code', header: 'Code', link: (r) => ['/templates', r.id] },
    { key: 'name', header: 'Name' },
    { key: 'channel', header: 'Channel' },
    { key: 'category', header: 'Category' },
    { key: 'versionCounts', header: 'Versions', value: (r) => Object.entries(r.versionCounts ?? {}).map(([s, n]) => `${s} ${n}`).join(', ') },
    { key: 'latestVersionNo', header: 'Latest version' },
    { key: 'publishedLocales', header: 'Published locales', value: (r) => (r.publishedLocales ?? []).join(', ') },
    { key: 'updatedAt', header: 'Updated', type: 'date' },
  ];

  constructor() {
    this.reload();
  }

  reload(): void {
    this.state.load(this.api.list({ ...this.form.getRawValue(), page: this.page(), size: this.size() }));
  }

  apply(): void {
    this.page.set(0);
    this.reload();
  }

  onPage(e: PageChange): void {
    this.page.set(e.page);
    this.size.set(e.size);
    this.reload();
  }

  create(v: TemplateEditorValue): void {
    this.saving.set(true);
    this.createError.set(null);
    this.api.create({
      code: v.code, name: v.name.trim(), channel: v.channel, category: v.category, locale: v.locale,
      subject: v.subject || undefined, body: v.body, variables: v.variables,
    }).subscribe({
      next: (d) => { this.saving.set(false); this.creating.set(false); void this.router.navigate(['/templates', d.id]); },
      error: (e: unknown) => { this.saving.set(false); this.createError.set(toNormalizedError(e)); },
    });
  }
}
