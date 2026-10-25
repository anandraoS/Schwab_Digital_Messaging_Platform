import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { EventsService } from '../../core/api/events.service';
import { ACCESS } from '../../core/auth/access';
import { AuthService } from '../../core/auth/auth.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { DeadLetter, PageResponse } from '../../core/models/api.models';
import { Loadable } from '../../core/util/loadable';
import { ConfirmService } from '../../shared/confirm-dialog';
import { Column, DataTableComponent } from '../../shared/data-table';
import { ErrorBannerComponent } from '../../shared/error-banner';
import { PageChange } from '../../shared/pager';

@Component({
  selector: 'app-dead-letters-page',
  imports: [MatButtonModule, DataTableComponent, ErrorBannerComponent],
  template: `
    <div class="page">
      <div class="toolbar-row"><h1 class="page-title grow">Dead letters</h1><button mat-stroked-button type="button" (click)="reload()">Refresh</button></div>
      <app-error-banner [error]="actionError()" [retryable]="false" />
      @if (notice()) { <p role="status" class="ok">{{ notice() }}</p> }
      <app-data-table caption="Dead-lettered messages" [columns]="columns" [rows]="state.data()?.content ?? []" [loading]="state.loading()"
        [error]="state.error()" [total]="state.data()?.totalElements ?? 0" [page]="page()" [size]="size()"
        [rowActions]="canAct() ? actionsTpl : null" emptyTitle="Dead-letter queue is empty" emptyMessage="No messages exhausted their retries."
        (pageChange)="onPage($event)" (retry)="reload()" />
      <ng-template #actionsTpl let-row>
        @if (!row.discarded) {
          <button mat-stroked-button type="button" (click)="replay(row)">Replay</button>
          <button mat-button type="button" (click)="discard(row)">Discard</button>
        } @else { <span class="muted">discarded</span> }
      </ng-template>
    </div>
  `,
  styles: `.ok { color: #0b5a22; }`,
})
export class DeadLettersPage {
  private readonly api = inject(EventsService);
  private readonly auth = inject(AuthService);
  private readonly confirmSvc = inject(ConfirmService);

  readonly state = new Loadable<PageResponse<DeadLetter>>();
  readonly page = signal(0);
  readonly size = signal(20);
  readonly actionError = signal<NormalizedError | null>(null);
  readonly notice = signal('');
  readonly canAct = () => this.auth.hasAnyRole(ACCESS.deadLetterAct);

  readonly columns: Column<DeadLetter>[] = [
    { key: 'messageId', header: 'Message id', type: 'mono', link: (r) => ['/messages', r.messageId] },
    { key: 'channel', header: 'Channel' }, { key: 'reason', header: 'Reason', type: 'status' },
    { key: 'attemptCount', header: 'Attempts' }, { key: 'deadLetteredAt', header: 'Dead-lettered', type: 'date' },
    { key: 'discarded', header: 'Discarded', type: 'bool' },
  ];

  constructor() {
    this.reload();
  }

  reload(): void {
    this.state.load(this.api.deadLetters({ page: this.page(), size: this.size() }));
  }

  onPage(e: PageChange): void {
    this.page.set(e.page);
    this.size.set(e.size);
    this.reload();
  }

  replay(row: DeadLetter): void {
    this.act(row, 'Replay message?', `Message ${row.messageId} returns to the queue and will be delivered again.`, 'Replay', false,
      () => this.api.replay(row.messageId), 'replayed');
  }

  discard(row: DeadLetter): void {
    this.act(row, 'Discard message?', `Message ${row.messageId} stays dead-lettered but is marked discarded (audited). This cannot be undone from the UI.`,
      'Discard', true, () => this.api.discard(row.messageId), 'discarded');
  }

  private act(row: DeadLetter, title: string, message: string, confirmLabel: string, danger: boolean,
              call: () => ReturnType<EventsService['replay']>, verb: string): void {
    this.actionError.set(null);
    this.notice.set('');
    this.confirmSvc.confirm({ title, message, confirmLabel, danger }).subscribe((ok) => {
      if (!ok) { return; }
      call().subscribe({
        next: () => { this.notice.set(`Message ${row.messageId} ${verb}.`); this.reload(); },
        error: (e: unknown) => this.actionError.set(toNormalizedError(e)),
      });
    });
  }
}
