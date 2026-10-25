import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';

export interface PageChange {
  page: number;
  size: number;
}

/** Thin wrapper over MatPaginator using zero-based pages like the API. */
@Component({
  selector: 'app-pager',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatPaginatorModule],
  template: `
    <mat-paginator
      [length]="total()"
      [pageIndex]="page()"
      [pageSize]="size()"
      [pageSizeOptions]="sizes()"
      [showFirstLastButtons]="true"
      aria-label="Select page"
      (page)="onPage($event)" />
  `,
})
export class PagerComponent {
  readonly total = input(0);
  readonly page = input(0);
  readonly size = input(20);
  readonly sizes = input<number[]>([10, 20, 50, 100]);
  readonly pageChange = output<PageChange>();

  onPage(e: PageEvent): void {
    this.pageChange.emit({ page: e.pageIndex, size: e.pageSize });
  }
}
