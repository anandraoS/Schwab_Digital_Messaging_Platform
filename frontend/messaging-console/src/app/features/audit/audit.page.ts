import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { EventsService } from '../../core/api/events.service';
import { AuditEvent, AuditFilter, PageResponse } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { Column, DataTableComponent } from '../../shared/data-table';
import { PageChange } from '../../shared/pager';
import { dayBoundary } from '../messages/message-list.page';

/** Audit detail may be a string or an object; always render it as plain text. */
export function auditDetailText(e: Pick<AuditEvent, 'detail'>): string {
  const d = e.detail;
  if (d === null || d === undefined) {
    return '';
  }
  return typeof d === 'string' ? d : JSON.stringify(d);
}

@Component({
  selector: 'app-audit-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, DataTableComponent],
  template: `
    <div class="page">
      <h1 class="page-title">Audit history</h1>
      <form class="filters" [formGroup]="form" (ngSubmit)="apply()" aria-label="Audit filters">
        <mat-form-field><mat-label>Entity type</mat-label><input matInput formControlName="entityType" placeholder="MESSAGE, TEMPLATE…" /></mat-form-field>
        <mat-form-field><mat-label>Entity id</mat-label><input matInput formControlName="entityId" /></mat-form-field>
        <mat-form-field><mat-label>Action</mat-label><input matInput formControlName="action" /></mat-form-field>
        <mat-form-field><mat-label>Actor</mat-label><input matInput formControlName="actor" /></mat-form-field>
        <mat-form-field><mat-label>From (UTC date)</mat-label><input matInput type="date" formControlName="from" /></mat-form-field>
        <mat-form-field><mat-label>To (UTC date)</mat-label><input matInput type="date" formControlName="to" /></mat-form-field>
        <div class="toolbar-row"><button mat-flat-button type="submit">Search</button><button mat-button type="button" (click)="clear()">Clear</button></div>
      </form>
      <app-data-table caption="Audit events" [columns]="columns" [rows]="state.data()?.content ?? []" [loading]="state.loading()"
        [error]="state.error()" [total]="state.data()?.totalElements ?? 0" [page]="page()" [size]="size()"
        emptyTitle="No audit events" emptyMessage="No events match the filters." (pageChange)="onPage($event)" (retry)="reload()" />
    </div>
  `,
})
export class AuditPage {
  private readonly api = inject(EventsService);
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly form = this.fb.group({ entityType: [''], entityId: [''], action: [''], actor: [''], from: [''], to: [''] });
  readonly state = new Loadable<PageResponse<AuditEvent>>();
  readonly page = signal(0);
  readonly size = signal(20);

  readonly columns: Column<AuditEvent>[] = [
    { key: 'occurredAt', header: 'When', type: 'date' }, { key: 'action', header: 'Action' },
    { key: 'actor', header: 'Actor' }, { key: 'actorRole', header: 'Role' }, { key: 'entityType', header: 'Entity' },
    { key: 'entityId', header: 'Entity id', type: 'mono' }, { key: 'correlationId', header: 'Correlation', type: 'mono' },
    { key: 'detail', header: 'Detail', value: (e) => auditDetailText(e) },
  ];

  constructor() {
    this.reload();
  }

  filter(): AuditFilter {
    const v = this.form.getRawValue();
    return { entityType: v.entityType.trim(), entityId: v.entityId.trim(), action: v.action.trim(), actor: v.actor.trim(),
      from: dayBoundary(v.from, false), to: dayBoundary(v.to, true), page: this.page(), size: this.size() };
  }

  reload(): void {
    this.state.load(this.api.audit(this.filter()));
  }

  apply(): void {
    this.page.set(0);
    this.reload();
  }

  clear(): void {
    this.form.reset();
    this.apply();
  }

  onPage(e: PageChange): void {
    this.page.set(e.page);
    this.size.set(e.size);
    this.reload();
  }
}
