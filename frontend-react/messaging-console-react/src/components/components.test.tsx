import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { NormalizedError } from '../core/http/errorMapper';
import type { HistoryEntry } from '../core/models';
import { BarChart, DonutChart, buildSlices } from './Charts';
import { ConfirmProvider, useConfirm } from './ConfirmDialog';
import { DataTable, type Column } from './DataTable';
import { EmptyState } from './EmptyState';
import { ErrorBanner } from './ErrorBanner';
import { MaskedValue, maskValue } from './MaskedValue';
import { Pager } from './Pager';
import { StatusChip, toneFor } from './StatusChip';
import { Timeline, detailText } from './Timeline';
import { formatAge, fmtDate, pct } from './format';

describe('StatusChip', () => {
  it('maps statuses to tones and always renders the label text', () => {
    expect(toneFor('DELIVERED')).toBe('ok');
    expect(toneFor('queued')).toBe('info');
    expect(toneFor('RETRY_PENDING')).toBe('warn');
    expect(toneFor('DEAD_LETTERED')).toBe('bad');
    expect(toneFor('CANCELLED')).toBe('neutral');
    expect(toneFor('SOMETHING_NEW')).toBe('neutral');
    expect(toneFor(null)).toBe('neutral');
  });

  it('renders readable text with an accessible label', () => {
    render(<StatusChip status="SENT_TO_PROVIDER" />);
    const chip = screen.getByLabelText('Status SENT TO PROVIDER');
    expect(chip).toHaveTextContent('SENT TO PROVIDER');
    expect(chip).toHaveClass('tone-info');
  });

  it('shows UNKNOWN for an empty status', () => {
    render(<StatusChip status="" />);
    expect(screen.getByText('UNKNOWN')).toBeInTheDocument();
  });
});

describe('MaskedValue', () => {
  it('keeps already-masked values and masks full addresses defensively', () => {
    expect(maskValue('a***@example.test')).toBe('a***@example.test');
    expect(maskValue('jane.doe@example.com')).toBe('j***@example.com');
    expect(maskValue('+14155550100')).toBe('+141***0100');
    expect(maskValue('abc')).toBe('***');
    expect(maskValue('')).toBe('—');
    render(<MaskedValue value="jane@example.com" />);
    expect(screen.queryByText('jane@example.com')).not.toBeInTheDocument();
    expect(screen.getByText('j***@example.com')).toBeInTheDocument();
  });
});

describe('EmptyState and ErrorBanner', () => {
  it('renders title, message and children', () => {
    render(<EmptyState title="Nothing" message="Try later"><a href="/x">go</a></EmptyState>);
    expect(screen.getByRole('status')).toHaveTextContent('Nothing');
    expect(screen.getByText('Try later')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'go' })).toBeInTheDocument();
  });

  it('shows actionable text, field details, correlation id and a working retry', async () => {
    const onRetry = vi.fn();
    const err = new NormalizedError({
      status: 400, code: 'VALIDATION_FAILED', message: 'Some fields are invalid.', serverMessage: 'channel is required',
      details: [{ field: 'channel', message: 'required' }], correlationId: 'corr-1', retryAfterSeconds: 3,
    });
    render(<ErrorBanner error={err} onRetry={onRetry} />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Some fields are invalid.');
    expect(alert).toHaveTextContent('channel is required');
    expect(alert).toHaveTextContent('channel: required');
    expect(alert).toHaveTextContent('correlationId=corr-1');
    expect(alert).toHaveTextContent('retry after 3s');
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('renders nothing without an error and hides Retry when not retryable', () => {
    const { container, rerender } = render(<ErrorBanner error={null} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<ErrorBanner error={new NormalizedError({ status: 500, code: 'X', message: 'm' })} retryable={false} onRetry={() => undefined} />);
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });
});

interface Row { id: string; status: string; at: string | null; flag: boolean; addr: string; note?: string }
const columns: Column<Row>[] = [
  { key: 'id', header: 'Id', type: 'mono', link: (r) => `/things/${r.id}` },
  { key: 'status', header: 'Status', type: 'status' },
  { key: 'at', header: 'When', type: 'date' },
  { key: 'flag', header: 'Flag', type: 'bool' },
  { key: 'addr', header: 'Address', type: 'masked' },
  { key: 'note', header: 'Note' },
];
const rows: Row[] = [
  { id: 'a1', status: 'DELIVERED', at: '2030-01-02T03:04:05Z', flag: true, addr: 'jane@example.com', note: '<b>bold</b>' },
  { id: 'a2', status: 'FAILED', at: null, flag: false, addr: '' },
];

describe('DataTable', () => {
  const renderTable = (props: Partial<React.ComponentProps<typeof DataTable<Row>>> = {}) =>
    render(<MemoryRouter><DataTable columns={columns} rows={rows} caption="Things" {...props} /></MemoryRouter>);

  it('renders typed cells as text, links, chips and masked values', () => {
    renderTable();
    const table = screen.getByRole('table', { name: 'Things' });
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Id', 'Status', 'When', 'Flag', 'Address', 'Note']);
    expect(within(table).getByRole('link', { name: 'a1' })).toHaveAttribute('href', '/things/a1');
    expect(within(table).getByText('DELIVERED')).toBeInTheDocument();
    expect(within(table).getByText('Yes')).toBeInTheDocument();
    expect(within(table).getByText('No')).toBeInTheDocument();
    expect(within(table).getByText('j***@example.com')).toBeInTheDocument();
    expect(within(table).getAllByText('—').length).toBeGreaterThan(0);
  });

  it('never interprets cell values as HTML', () => {
    const { container } = renderTable();
    expect(screen.getByText('<b>bold</b>')).toBeInTheDocument();
    expect(container.querySelector('b')).toBeNull();
  });

  it('shows the empty state when there are no rows', () => {
    renderTable({ rows: [], emptyTitle: 'No things', emptyMessage: 'Add one' });
    expect(screen.getByText('No things')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows a loading bar and hides the empty state while loading', () => {
    renderTable({ rows: [], loading: true });
    expect(screen.getByRole('progressbar', { name: 'Loading data' })).toBeInTheDocument();
    expect(screen.queryByText('No results')).not.toBeInTheDocument();
  });

  it('shows the error banner instead of rows and wires Retry', async () => {
    const onRetry = vi.fn();
    renderTable({ error: new NormalizedError({ status: 500, code: 'INTERNAL_ERROR', message: 'broken' }), onRetry });
    expect(screen.getByRole('alert')).toHaveTextContent('broken');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalled();
  });

  it('renders row actions in an Actions column', async () => {
    const act = vi.fn();
    renderTable({ rowActions: (r) => <button type="button" onClick={() => act(r.id)}>Do {r.id}</button> });
    expect(screen.getByRole('columnheader', { name: 'Actions' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Do a2' }));
    expect(act).toHaveBeenCalledWith('a2');
  });

  it('renders the pager when a total is given and reports page changes', async () => {
    const onPageChange = vi.fn();
    renderTable({ total: 45, page: 0, size: 20, onPageChange });
    expect(screen.getByTestId('pager-range')).toHaveTextContent('1–20 of 45');
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(onPageChange).toHaveBeenCalledWith({ page: 1, size: 20 });
  });
});

describe('Pager', () => {
  it('disables first/previous on the first page and next/last on the last page', () => {
    const { rerender } = render(<Pager total={45} page={0} size={20} onChange={() => undefined} />);
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'First page' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeEnabled();
    rerender(<Pager total={45} page={2} size={20} onChange={() => undefined} />);
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Last page' })).toBeDisabled();
    expect(screen.getByTestId('pager-range')).toHaveTextContent('41–45 of 45');
  });

  it('changing the page size goes back to the first page; last jumps to the last page', async () => {
    const onChange = vi.fn();
    render(<Pager total={95} page={1} size={20} onChange={onChange} />);
    await userEvent.selectOptions(screen.getByLabelText(/Rows per page/), '50');
    expect(onChange).toHaveBeenLastCalledWith({ page: 0, size: 50 });
    await userEvent.click(screen.getByRole('button', { name: 'Last page' }));
    expect(onChange).toHaveBeenLastCalledWith({ page: 4, size: 20 });
  });
});

describe('Timeline', () => {
  const entries: HistoryEntry[] = [
    { at: '2030-01-01T10:00:02Z', kind: 'DELIVERY_EVENT', type: 'DELIVERED', summary: 'Provider receipt DELIVERED', detail: { providerId: 'acme' } },
    { at: '2030-01-01T10:00:00Z', kind: 'AUDIT', type: 'MESSAGE_ACCEPTED', summary: 'Message accepted', detail: { actor: 'sender-retail', to: 'ACCEPTED', from: null, ip: '' } },
    { at: '2030-01-01T10:00:01Z', kind: 'ATTEMPT', type: 'ATTEMPT', summary: 'Attempt 1', detail: null },
  ];

  it('orders entries oldest-first and shows actor from detail.actor and status from detail.to', () => {
    render(<Timeline entries={entries} />);
    const items = within(screen.getByRole('list', { name: 'Message lifecycle timeline' })).getAllByRole('listitem');
    expect(items.map((i) => within(i).getByText(/accepted|Attempt|receipt/).textContent)).toEqual(['Message accepted', 'Attempt 1', 'Provider receipt DELIVERED']);
    expect(items[0]).toHaveTextContent('sender-retail');
    expect(within(items[0]).getByLabelText('Status ACCEPTED')).toBeInTheDocument();
    expect(within(items[2]).getByLabelText('Status DELIVERED')).toBeInTheDocument();
    expect(items[1]).not.toHaveTextContent('undefined');
  });

  it('detail text omits actor, target status and blank values', () => {
    expect(detailText(entries[1])).toBe('');
    expect(detailText(entries[0])).toBe('providerId: acme');
    expect(detailText({ at: 'x', kind: 'AUDIT', type: 't', summary: 's', detail: { n: { a: 1 } } })).toBe('n: {"a":1}');
  });
});

describe('ConfirmDialog', () => {
  function Harness({ onResult }: { onResult: (ok: boolean) => void }) {
    const confirm = useConfirm();
    return <button type="button" onClick={() => void confirm({ title: 'Really?', message: 'This is final.', confirmLabel: 'Yes do it', danger: true }).then(onResult)}>open</button>;
  }

  it('opens a modal dialog with Cancel focused, and resolves true on confirm', async () => {
    const onResult = vi.fn();
    render(<ConfirmProvider><Harness onResult={onResult} /></ConfirmProvider>);
    await userEvent.click(screen.getByRole('button', { name: 'open' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Really?' });
    expect(dlg).toHaveAttribute('aria-modal', 'true');
    expect(dlg).toHaveAccessibleDescription('This is final.');
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.click(screen.getByRole('button', { name: 'Yes do it' }));
    expect(onResult).toHaveBeenCalledWith(true);
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('resolves false on Cancel and on Escape, and restores focus to the trigger', async () => {
    const onResult = vi.fn();
    render(<ConfirmProvider><Harness onResult={onResult} /></ConfirmProvider>);
    const trigger = screen.getByRole('button', { name: 'open' });
    await userEvent.click(trigger);
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onResult).toHaveBeenLastCalledWith(false);
    expect(trigger).toHaveFocus();
    await userEvent.click(trigger);
    await userEvent.keyboard('{Escape}');
    expect(onResult).toHaveBeenCalledTimes(2);
    expect(onResult).toHaveBeenLastCalledWith(false);
  });

  it('useConfirm throws outside the provider', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => render(<Harness onResult={() => undefined} />)).toThrow(/ConfirmProvider/);
    spy.mockRestore();
  });
});

describe('charts and formatters', () => {
  it('donut slices add up and skip zero values', () => {
    const s = buildSlices([{ label: 'EMAIL', value: 3 }, { label: 'SMS', value: 1 }, { label: 'PUSH', value: 0 }]);
    expect(s.map((x) => x.label)).toEqual(['EMAIL', 'SMS']);
    expect(s.map((x) => x.percent)).toEqual([75, 25]);
    expect(buildSlices([])).toEqual([]);
    expect(buildSlices([{ label: 'ONE', value: 5 }])[0].path).toContain('A');
  });

  it('bar chart has an accessible title and a table fallback', () => {
    render(<BarChart data={[{ label: 'QUEUED', value: 4 }]} title="By status" />);
    expect(screen.getByRole('img', { name: 'By status' })).toBeInTheDocument();
    expect(screen.getByRole('row', { name: /QUEUED 4/ })).toBeInTheDocument();
  });

  it('donut legend shows counts and percentages (or No data)', () => {
    const { rerender } = render(<DonutChart data={[{ label: 'EMAIL', value: 2 }]} title="By channel" />);
    expect(screen.getByText(/EMAIL:/)).toHaveTextContent('EMAIL: 2 (100%)');
    rerender(<DonutChart data={[]} title="By channel" />);
    expect(screen.getByText('No data')).toBeInTheDocument();
  });

  it('formats ages, percentages and dates', () => {
    expect(formatAge(3725)).toBe('1h 2m 5s');
    expect(formatAge(59)).toBe('59s');
    expect(formatAge(null)).toBe('—');
    expect(formatAge(-1)).toBe('—');
    expect(pct(0.1234)).toBe('12.3%');
    expect(pct(undefined)).toBe('—');
    expect(fmtDate('')).toBe('');
    expect(fmtDate('garbage')).toBe('garbage');
    expect(fmtDate('2030-01-02T03:04:05.006Z', true)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$/);
  });
});
