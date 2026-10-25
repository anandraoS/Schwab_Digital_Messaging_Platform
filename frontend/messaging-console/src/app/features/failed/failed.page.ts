import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { EventsService } from '../../core/api/events.service';
import { MessagesService } from '../../core/api/messages.service';
import { ACCESS } from '../../core/auth/access';
import { AuthService } from '../../core/auth/auth.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { FailedMessage, PageResponse } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { ConfirmService } from '../../shared/confirm-dialog';
import { Column, DataTableComponent } from '../../shared/data-table';
import { ErrorBannerComponent } from '../../shared/error-banner';
import { PageChange } from '../../shared/pager';

@Component({
  selector: 'app-failed-page',
  imports: [MatButtonModule, DataTableComponent, ErrorBannerComponent],
  template: `
    <div class="page">
      <div class="toolbar-row"><h1 class="page-title grow">Failed messages</h1><button mat-stroked-button type="button" (click)="reload()">Refresh</button></div>
      <p class="muted">Investigation list: FAILED, DELIVERY_UNKNOWN and RETRY_PENDING messages. DELIVERY_UNKNOWN is never retried blindly; wait for a callback or reconciliation.</p>
      <app-error-banner [error]="actionError()" [retryable]="false" />
      @if (notice()) { <p role="status" class="ok">{{ notice() }}</p> }
      <app-data-table caption="Failed messages" [columns]="columns" [rows]="state.data()?.content ?? []" [loading]="state.loading()"
        [error]="state.error()" [total]="state.data()?.totalElements ?? 0" [page]="page()" [size]="size()" [rowActions]="actionsTpl"
        emptyTitle="No failed messages" emptyMessage="Everything is flowing." (pageChange)="onPage($event)" (retry)="reload()" />
      <ng-template #actionsTpl let-row>
        @if (canRequeue() && row.status === 'FAILED') { <button mat-stroked-button type="button" (click)="requeue(row)">Requeue</button> }
      </ng-template>
    </div>
  `,
  styles: `.ok { color: #0b5a22; }`,
})
export class FailedPage {
  private readonly events = inject(EventsService);
  private readonly messages = inject(MessagesService);
  private readonly auth = inject(AuthService);
  private readonly confirmSvc = inject(ConfirmService);

  readonly state = new Loadable<PageResponse<FailedMessage>>();
  readonly page = signal(0);
  readonly size = signal(20);
  readonly actionError = signal<NormalizedError | null>(null);
  readonly notice = signal('');
  readonly canRequeue = () => this.auth.hasAnyRole(ACCESS.requeue);

  readonly columns: Column<FailedMessage>[] = [
    { key: 'id', header: 'Message id', type: 'mono', link: (r) => ['/messages', r.id] },
    { key: 'status', header: 'Status', type: 'status' }, { key: 'channel', header: 'Channel' },
    { key: 'statusReason', header: 'Reason' }, { key: 'attemptCount', header: 'Attempts' },
    { key: 'nextAttemptAt', header: 'Next attempt', type: 'date' }, { key: 'updatedAt', header: 'Updated', type: 'date' },
  ];

  constructor() {
    this.reload();
  }

  reload(): void {
    this.state.load(this.events.failed({ page: this.page(), size: this.size() }));
  }

  onPage(e: PageChange): void {
    this.page.set(e.page);
    this.size.set(e.size);
    this.reload();
  }

  requeue(row: FailedMessage): void {
    this.actionError.set(null);
    this.notice.set('');
    this.confirmSvc.confirm({ title: 'Requeue message?', message: `Message ${row.id} will be queued for another delivery attempt.`, confirmLabel: 'Requeue' })
      .subscribe((ok) => {
        if (!ok) { return; }
        this.messages.requeue(row.id).subscribe({
          next: () => { this.notice.set(`Message ${row.id} requeued.`); this.reload(); },
          error: (e: unknown) => this.actionError.set(toNormalizedError(e)),
        });
      });
  }
}
