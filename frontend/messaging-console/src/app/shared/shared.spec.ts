import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { NormalizedError } from '../core/http/error-mapper';
import { HistoryEntry } from '../core/models/api.models';
import { BarChartComponent, DonutChartComponent, arcPath, buildSlices } from './charts';
import { ConfirmService } from './confirm-dialog';
import { Column, DataTableComponent } from './data-table';
import { EmptyStateComponent } from './empty-state';
import { ErrorBannerComponent } from './error-banner';
import { MaskedValueComponent, maskValue } from './masked-value';
import { PagerComponent } from './pager';
import { StatusChipComponent, toneFor } from './status-chip';
import { TimelineComponent } from './timeline';

const text = (f: ComponentFixture<unknown>): string => (f.nativeElement as HTMLElement).textContent ?? '';
const q = <T extends Element>(f: ComponentFixture<unknown>, sel: string): T | null => (f.nativeElement as HTMLElement).querySelector<T>(sel);

describe('StatusChipComponent', () => {
  function chip(status: string | null): ComponentFixture<StatusChipComponent> {
    const f = TestBed.createComponent(StatusChipComponent);
    f.componentRef.setInput('status', status);
    f.detectChanges();
    return f;
  }

  it('maps statuses to tones', () => {
    expect(toneFor('DELIVERED')).toBe('ok');
    expect(toneFor('retry_pending')).toBe('warn');
    expect(toneFor('FAILED')).toBe('bad');
    expect(toneFor('QUEUED')).toBe('info');
    expect(toneFor('CANCELLED')).toBe('neutral');
    expect(toneFor('SOMETHING_NEW')).toBe('neutral');
    expect(toneFor(null)).toBe('neutral');
  });

  it('renders readable text and a tone class (colour is not the only signal)', () => {
    const f = chip('SENT_TO_PROVIDER');
    expect(text(f).trim()).toBe('SENT TO PROVIDER');
    expect(q(f, '.chip')!.className).toContain('tone-info');
    expect(q(f, '.chip')!.getAttribute('aria-label')).toBe('Status SENT TO PROVIDER');
  });

  it('shows UNKNOWN for empty status', () => {
    expect(text(chip('')).trim()).toBe('UNKNOWN');
  });
});

describe('masking', () => {
  it('leaves already-masked values untouched', () => {
    expect(maskValue('r***@example.test')).toBe('r***@example.test');
  });
  it('masks unmasked e-mail addresses defensively', () => {
    expect(maskValue('jane.doe@example.test')).toBe('j***@example.test');
  });
  it('masks unmasked phone numbers defensively', () => {
    expect(maskValue('+15550100001')).toBe('+155***0001');
    expect(maskValue('123')).toBe('***');
  });
  it('shows a dash for empty values', () => {
    expect(maskValue('')).toBe('—');
    expect(maskValue(null)).toBe('—');
  });
  it('MaskedValueComponent never prints the full address', () => {
    const f = TestBed.createComponent(MaskedValueComponent);
    f.componentRef.setInput('value', 'someone@example.test');
    f.detectChanges();
    expect(text(f)).not.toContain('someone');
  });
});

describe('ErrorBannerComponent', () => {
  const err: NormalizedError = {
    status: 400, code: 'VALIDATION_FAILED', message: 'Fix fields', serverMessage: 'Validation failed',
    details: [{ field: 'channel', message: 'required' }], correlationId: 'cid-9', conflict: false,
  };

  it('shows message, details, code and correlation id with role=alert', () => {
    const f = TestBed.createComponent(ErrorBannerComponent);
    f.componentRef.setInput('error', err);
    f.detectChanges();
    expect(q(f, '[role=alert]')).not.toBeNull();
    expect(text(f)).toContain('Fix fields');
    expect(text(f)).toContain('channel');
    expect(text(f)).toContain('code=VALIDATION_FAILED');
    expect(text(f)).toContain('cid-9');
  });

  it('renders nothing without an error', () => {
    const f = TestBed.createComponent(ErrorBannerComponent);
    f.detectChanges();
    expect(q(f, '[role=alert]')).toBeNull();
  });

  it('emits retry when the button is clicked and hides it when not retryable', () => {
    const f = TestBed.createComponent(ErrorBannerComponent);
    f.componentRef.setInput('error', err);
    let n = 0;
    f.componentInstance.retry.subscribe(() => n++);
    f.detectChanges();
    q<HTMLButtonElement>(f, 'button')!.click();
    expect(n).toBe(1);
    f.componentRef.setInput('retryable', false);
    f.detectChanges();
    expect(q(f, 'button')).toBeNull();
  });
});

describe('EmptyStateComponent', () => {
  it('renders title and message', () => {
    const f = TestBed.createComponent(EmptyStateComponent);
    f.componentRef.setInput('title', 'No data');
    f.componentRef.setInput('message', 'Try later');
    f.detectChanges();
    expect(text(f)).toContain('No data');
    expect(text(f)).toContain('Try later');
  });
});

describe('TimelineComponent', () => {
  it('orders entries oldest first and escapes content', () => {
    const f = TestBed.createComponent(TimelineComponent);
    const entries: HistoryEntry[] = [
      { at: '2026-01-01T10:00:02Z', kind: 'AUDIT', type: 'B', summary: 'Second', detail: { actor: 'op-1', to: 'QUEUED' } },
      { at: '2026-01-01T10:00:01Z', kind: 'AUDIT', type: 'A', summary: 'First', detail: { note: '<img src=x onerror=alert(1)>' } },
    ];
    f.componentRef.setInput('entries', entries);
    f.detectChanges();
    const titles = Array.from((f.nativeElement as HTMLElement).querySelectorAll('strong')).map((e) => e.textContent);
    expect(titles).toEqual(['First', 'Second']);
    expect(q(f, 'img')).toBeNull();
    expect(text(f)).toContain('<img src=x');
    expect(text(f)).toContain('op-1');
    expect(q(f, 'app-status-chip')).not.toBeNull(); // detail.to is rendered as the status chip
  });
});

describe('charts', () => {
  it('buildSlices computes percentages and skips zero values', () => {
    const s = buildSlices([{ label: 'A', value: 3 }, { label: 'B', value: 1 }, { label: 'C', value: 0 }]);
    expect(s.length).toBe(2);
    expect(s[0].percent).toBe(75);
    expect(s[1].percent).toBe(25);
  });

  it('buildSlices returns nothing for empty data and survives a single 100% slice', () => {
    expect(buildSlices([])).toEqual([]);
    const one = buildSlices([{ label: 'A', value: 5 }]);
    expect(one.length).toBe(1);
    expect(one[0].path).toContain('A');
  });

  it('arcPath uses the large-arc flag past 180 degrees', () => {
    expect(arcPath(0, 0, 10, 5, 0, Math.PI * 1.5)).toContain(' 0 1 1 ');
    expect(arcPath(0, 0, 10, 5, 0, Math.PI / 2)).toContain(' 0 0 1 ');
  });

  it('BarChartComponent scales bars relative to the maximum and offers an accessible table', () => {
    const f = TestBed.createComponent(BarChartComponent);
    f.componentRef.setInput('data', [{ label: 'X', value: 10 }, { label: 'Y', value: 5 }]);
    f.detectChanges();
    const rects = (f.nativeElement as HTMLElement).querySelectorAll('rect');
    expect(rects.length).toBe(2);
    expect(Number(rects[0].getAttribute('width'))).toBeGreaterThan(Number(rects[1].getAttribute('width')));
    expect(q(f, 'svg')!.getAttribute('role')).toBe('img');
    expect((f.nativeElement as HTMLElement).querySelectorAll('table tr').length).toBe(2);
  });

  it('DonutChartComponent shows total and legend, or "No data"', () => {
    const f = TestBed.createComponent(DonutChartComponent);
    f.detectChanges();
    expect(text(f)).toContain('No data');
    f.componentRef.setInput('data', [{ label: 'EMAIL', value: 2 }, { label: 'SMS', value: 2 }]);
    f.detectChanges();
    expect(text(f)).toContain('EMAIL');
    expect(text(f)).toContain('50%');
  });
});

describe('PagerComponent', () => {
  it('emits zero-based page changes', () => {
    const f = TestBed.createComponent(PagerComponent);
    f.componentRef.setInput('total', 100);
    f.componentRef.setInput('size', 10);
    f.detectChanges();
    const events: { page: number; size: number }[] = [];
    f.componentInstance.pageChange.subscribe((e) => events.push(e));
    f.componentInstance.onPage({ pageIndex: 3, pageSize: 25, length: 100, previousPageIndex: 2 });
    expect(events).toEqual([{ page: 3, size: 25 }]);
  });
});

@Component({
  imports: [DataTableComponent],
  template: `
    <ng-template #act let-row><button class="act">{{ row.id }}</button></ng-template>
    <app-data-table [columns]="cols" [rows]="rows" [loading]="loading" [error]="error" [total]="total" [page]="0" [size]="10"
      [rowActions]="act" caption="Test table" />
  `,
})
class TableHostComponent {
  cols: Column[] = [
    { key: 'id', header: 'Id', link: (r) => ['/messages', r['id']] },
    { key: 'status', header: 'Status', type: 'status' },
    { key: 'note', header: 'Note' },
  ];
  rows: Record<string, unknown>[] = [];
  loading = false;
  error: NormalizedError | null = null;
  total = 0;
}

describe('DataTableComponent', () => {
  let f: ComponentFixture<TableHostComponent>;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    f = TestBed.createComponent(TableHostComponent);
  });

  it('shows the empty state when there are no rows', () => {
    f.detectChanges();
    expect(text(f)).toContain('No results');
    expect(q(f, 'table')).toBeNull();
  });

  it('shows a progress bar while loading', () => {
    f.componentInstance.loading = true;
    f.detectChanges();
    expect(q(f, 'mat-progress-bar')).not.toBeNull();
  });

  it('shows an error banner instead of the table on error', () => {
    f.componentInstance.error = { status: 500, code: 'INTERNAL_ERROR', message: 'Server broke', details: [], conflict: false };
    f.detectChanges();
    expect(text(f)).toContain('Server broke');
    expect(q(f, 'table')).toBeNull();
  });

  it('renders rows with links, status chips and escapes cell text', () => {
    const c = f.componentInstance;
    c.rows = [{ id: 'm1', status: 'DELIVERED', note: '<b>bold</b>' }];
    c.total = 1;
    f.detectChanges();
    expect(q(f, 'table')).not.toBeNull();
    expect(q(f, 'a')!.getAttribute('href')).toBe('/messages/m1');
    expect(q(f, 'app-status-chip')).not.toBeNull();
    expect(q(f, 'td b')).toBeNull();
    expect(text(f)).toContain('<b>bold</b>');
    expect(q(f, 'caption')!.textContent).toBe('Test table');
  });

  it('renders the row action template with the row as context', () => {
    f.componentInstance.rows = [{ id: 'm9', status: 'FAILED' }];
    f.detectChanges();
    expect(q(f, 'button.act')?.textContent).toBe('m9');
  });
});

describe('ConfirmService', () => {
  it('emits true only when the dialog closes with true', (done) => {
    const dialog = TestBed.inject(MatDialog);
    spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(true) } as never);
    const results: boolean[] = [];
    TestBed.inject(ConfirmService).confirm({ title: 't', message: 'm' }).subscribe((v) => { results.push(v); });
    (dialog.open as jasmine.Spy).and.returnValue({ afterClosed: () => of(undefined) });
    TestBed.inject(ConfirmService).confirm({ title: 't', message: 'm' }).subscribe((v) => { results.push(v); expect(results).toEqual([true, false]); done(); });
  });
});
