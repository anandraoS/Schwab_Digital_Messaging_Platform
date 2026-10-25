import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, TemplateRef, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { NormalizedError } from '../core/http/error-mapper';
import { EmptyStateComponent } from './empty-state';
import { ErrorBannerComponent } from './error-banner';
import { MaskedValueComponent } from './masked-value';
import { PageChange, PagerComponent } from './pager';
import { StatusChipComponent } from './status-chip';

export interface Column<T = Record<string, unknown>> {
  key: string;
  header: string;
  type?: 'text' | 'status' | 'date' | 'mono' | 'masked' | 'bool';
  /** Optional value extractor; defaults to row[key]. */
  value?: (row: T) => unknown;
  /** Optional router link for the cell. */
  link?: (row: T) => unknown[] | null;
}

/**
 * Generic table with loading, error and empty states and an optional pager.
 * Cell values are always interpolated as text (never innerHTML).
 */
@Component({
  selector: 'app-data-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe, NgTemplateOutlet, RouterLink, MatProgressBarModule, EmptyStateComponent, ErrorBannerComponent,
    MaskedValueComponent, PagerComponent, StatusChipComponent,
  ],
  template: `
    <app-error-banner [error]="error()" (retry)="retry.emit()" />
    @if (loading()) { <mat-progress-bar mode="indeterminate" aria-label="Loading data" /> }
    @if (!error()) {
      @if (!loading() && rows().length === 0) {
        <app-empty-state [title]="emptyTitle()" [message]="emptyMessage()" />
      } @else if (rows().length > 0) {
        <div class="scroll" tabindex="0" role="region" [attr.aria-label]="caption()">
          <table class="simple">
            <caption class="sr-only">{{ caption() }}</caption>
            <thead>
              <tr>
                @for (c of columns(); track c.key) { <th scope="col">{{ c.header }}</th> }
                @if (rowActions()) { <th scope="col">Actions</th> }
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track $index) {
                <tr>
                  @for (c of columns(); track c.key) {
                    <td>
                      @let v = cellValue(c, row);
                      @let l = linkFor(c, row);
                      @switch (c.type) {
                        @case ('status') { <app-status-chip [status]="str(v)" /> }
                        @case ('date') { @if (v) { {{ str(v) | date: 'yyyy-MM-dd HH:mm:ss' }} } @else { — } }
                        @case ('masked') { <app-masked-value [value]="str(v)" /> }
                        @case ('bool') { {{ v ? 'Yes' : 'No' }} }
                        @default {
                          @if (l) { <a [routerLink]="l" [class.mono]="c.type === 'mono'">{{ v ?? '—' }}</a> }
                          @else if (c.type === 'mono') { <span class="mono">{{ v ?? '—' }}</span> }
                          @else { {{ v ?? '—' }} }
                        }
                      }
                    </td>
                  }
                  @if (rowActions(); as tpl) {
                    <td><ng-container [ngTemplateOutlet]="tpl" [ngTemplateOutletContext]="{ $implicit: row }" /></td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      @if (total() > 0) {
        <app-pager [total]="total()" [page]="page()" [size]="size()" (pageChange)="pageChange.emit($event)" />
      }
    }
  `,
  styles: `.scroll { overflow-x: auto; border: 1px solid var(--mat-sys-outline-variant); border-radius: 8px; }`,
})
export class DataTableComponent {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  readonly columns = input.required<Column<any>[]>();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  readonly rows = input<any[]>([]);
  readonly loading = input(false);
  readonly error = input<NormalizedError | null>(null);
  readonly caption = input('Data table');
  readonly emptyTitle = input('No results');
  readonly emptyMessage = input('Nothing matches the current filters.');
  readonly total = input(0);
  readonly page = input(0);
  readonly size = input(20);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  readonly rowActions = input<TemplateRef<{ $implicit: any }> | null>(null);
  readonly pageChange = output<PageChange>();
  readonly retry = output<void>();

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  cellValue(c: Column<any>, row: any): unknown {
    return c.value ? c.value(row) : row?.[c.key];
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  linkFor(c: Column<any>, row: any): unknown[] | null {
    return c.link ? c.link(row) : null;
  }

  str(v: unknown): string {
    return v === null || v === undefined ? '' : String(v);
  }
}
