import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MessagesService } from '../../core/api/messages.service';
import { ACCESS } from '../../core/auth/access';
import { AuthService } from '../../core/auth/auth.service';
import {
  CATEGORIES, CHANNELS, MESSAGE_STATUSES, MessageFilter, MessageSummary, PRIORITIES, PageResponse,
} from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { Column, DataTableComponent } from '../../shared/data-table';
import { PageChange } from '../../shared/pager';

/** Converts a yyyy-MM-dd date input to an ISO instant at start/end of that UTC day. */
export function dayBoundary(date: string, end: boolean): string {
  return date ? `${date}T${end ? '23:59:59' : '00:00:00'}Z` : '';
}

@Component({
  selector: 'app-message-list-page',
  imports: [
    ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, DataTableComponent,
  ],
  template: `
    <div class="page">
      <div class="toolbar-row">
        <h1 class="page-title grow">Messages</h1>
        @if (canSubmit()) { <a mat-flat-button routerLink="/messages/new">New message</a> }
      </div>
      <form class="filters" [formGroup]="form" (ngSubmit)="apply()" aria-label="Message filters">
        <mat-form-field><mat-label>Status</mat-label>
          <mat-select formControlName="status"><mat-option value="">Any</mat-option>
            @for (s of statuses; track s) { <mat-option [value]="s">{{ s }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Channel</mat-label>
          <mat-select formControlName="channel"><mat-option value="">Any</mat-option>
            @for (s of channels; track s) { <mat-option [value]="s">{{ s }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Category</mat-label>
          <mat-select formControlName="category"><mat-option value="">Any</mat-option>
            @for (s of categories; track s) { <mat-option [value]="s">{{ s }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Priority</mat-label>
          <mat-select formControlName="priority"><mat-option value="">Any</mat-option>
            @for (s of priorities; track s) { <mat-option [value]="s">{{ s }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Recipient id</mat-label><input matInput formControlName="recipientId" /></mat-form-field>
        <mat-form-field><mat-label>Correlation id</mat-label><input matInput formControlName="correlationId" /></mat-form-field>
        <mat-form-field><mat-label>Client reference</mat-label><input matInput formControlName="q" /></mat-form-field>
        <mat-form-field><mat-label>From (UTC date)</mat-label><input matInput type="date" formControlName="from" /></mat-form-field>
        <mat-form-field><mat-label>To (UTC date)</mat-label><input matInput type="date" formControlName="to" /></mat-form-field>
        <div class="toolbar-row">
          <button mat-flat-button type="submit">Apply filters</button>
          <button mat-button type="button" (click)="clear()">Clear</button>
        </div>
      </form>
      <app-data-table
        caption="Messages"
        [columns]="columns"
        [rows]="state.data()?.content ?? []"
        [loading]="state.loading()"
        [error]="state.error()"
        [total]="state.data()?.totalElements ?? 0"
        [page]="page()"
        [size]="size()"
        emptyTitle="No messages found"
        emptyMessage="Try clearing filters or submit a new message."
        (pageChange)="onPage($event)"
        (retry)="reload()" />
    </div>
  `,
})
export class MessageListPage {
  private readonly api = inject(MessagesService);
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly auth = inject(AuthService);

  readonly statuses = MESSAGE_STATUSES;
  readonly channels = CHANNELS;
  readonly categories = CATEGORIES;
  readonly priorities = PRIORITIES;
  readonly canSubmit = () => this.auth.hasAnyRole(ACCESS.submit);

  readonly form = this.fb.group({
    status: [''], channel: [''], category: [''], priority: [''],
    recipientId: [''], correlationId: [''], q: [''], from: [''], to: [''],
  });
  readonly state = new Loadable<PageResponse<MessageSummary>>();
  readonly page = signal(0);
  readonly size = signal(20);

  readonly columns: Column<MessageSummary>[] = [
    { key: 'id', header: 'Message id', type: 'mono', link: (r) => ['/messages', r.id] },
    { key: 'status', header: 'Status', type: 'status' },
    { key: 'channel', header: 'Channel' },
    { key: 'channelUsed', header: 'Sent via', value: (r) => r.channelUsed ?? '' },
    { key: 'category', header: 'Category' },
    { key: 'priority', header: 'Priority' },
    { key: 'templateCode', header: 'Template' },
    { key: 'clientReference', header: 'Client ref' },
    { key: 'nextAttemptAt', header: 'Next attempt', type: 'date' },
    { key: 'createdAt', header: 'Created', type: 'date' },
  ];

  constructor() {
    this.reload();
  }

  buildFilter(): MessageFilter {
    const v = this.form.getRawValue();
    return {
      status: v.status, channel: v.channel, category: v.category, priority: v.priority,
      recipientId: v.recipientId.trim(), correlationId: v.correlationId.trim(), q: v.q.trim(),
      from: dayBoundary(v.from, false), to: dayBoundary(v.to, true),
      page: this.page(), size: this.size(), sort: 'createdAt,desc',
    };
  }

  reload(): void {
    this.state.load(this.api.list(this.buildFilter()));
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
