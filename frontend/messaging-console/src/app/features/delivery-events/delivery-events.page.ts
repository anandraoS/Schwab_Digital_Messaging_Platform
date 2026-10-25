import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { EventsService } from '../../core/api/events.service';
import { DELIVERY_EVENT_TYPES, DeliveryEvent, DeliveryEventFilter, PageResponse } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { Column, DataTableComponent } from '../../shared/data-table';
import { PageChange } from '../../shared/pager';
import { dayBoundary } from '../messages/message-list.page';

@Component({
  selector: 'app-delivery-events-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, DataTableComponent],
  template: `
    <div class="page">
      <h1 class="page-title">Delivery events</h1>
      <form class="filters" [formGroup]="form" (ngSubmit)="apply()" aria-label="Delivery event filters">
        <mat-form-field><mat-label>Message id</mat-label><input matInput formControlName="messageId" /></mat-form-field>
        <mat-form-field><mat-label>Provider id</mat-label><input matInput formControlName="providerId" /></mat-form-field>
        <mat-form-field><mat-label>Type</mat-label>
          <mat-select formControlName="type"><mat-option value="">Any</mat-option>
            @for (t of types; track t) { <mat-option [value]="t">{{ t }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Applied</mat-label>
          <mat-select formControlName="applied"><mat-option value="">Any</mat-option><mat-option value="true">Applied</mat-option><mat-option value="false">Ignored</mat-option></mat-select></mat-form-field>
        <mat-form-field><mat-label>From (UTC date)</mat-label><input matInput type="date" formControlName="from" /></mat-form-field>
        <mat-form-field><mat-label>To (UTC date)</mat-label><input matInput type="date" formControlName="to" /></mat-form-field>
        <div class="toolbar-row"><button mat-flat-button type="submit">Search</button><button mat-button type="button" (click)="clear()">Clear</button></div>
      </form>
      <app-data-table caption="Delivery events" [columns]="columns" [rows]="state.data()?.content ?? []" [loading]="state.loading()"
        [error]="state.error()" [total]="state.data()?.totalElements ?? 0" [page]="page()" [size]="size()"
        emptyTitle="No delivery events" emptyMessage="No events match the filters." (pageChange)="onPage($event)" (retry)="reload()" />
    </div>
  `,
})
export class DeliveryEventsPage {
  private readonly api = inject(EventsService);
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly types = DELIVERY_EVENT_TYPES;
  readonly form = this.fb.group({ messageId: [''], providerId: [''], type: [''], applied: [''], from: [''], to: [''] });
  readonly state = new Loadable<PageResponse<DeliveryEvent>>();
  readonly page = signal(0);
  readonly size = signal(20);

  readonly columns: Column<DeliveryEvent>[] = [
    { key: 'occurredAt', header: 'Occurred', type: 'date' },
    { key: 'type', header: 'Type', type: 'status' },
    { key: 'messageId', header: 'Message', type: 'mono', link: (r) => (r.messageId ? ['/messages', r.messageId] : null) },
    { key: 'providerId', header: 'Provider' }, { key: 'providerEventId', header: 'Provider event id', type: 'mono' },
    { key: 'applied', header: 'Applied', type: 'bool' }, { key: 'ignoredReason', header: 'Ignored reason' },
    { key: 'receivedAt', header: 'Received', type: 'date' },
  ];

  constructor() {
    this.reload();
  }

  filter(): DeliveryEventFilter {
    const v = this.form.getRawValue();
    return { ...v, messageId: v.messageId.trim(), providerId: v.providerId.trim(), from: dayBoundary(v.from, false), to: dayBoundary(v.to, true), page: this.page(), size: this.size() };
  }

  reload(): void {
    this.state.load(this.api.deliveryEvents(this.filter()));
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
