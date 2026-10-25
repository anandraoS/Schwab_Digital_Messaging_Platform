import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { NormalizedError } from '../core/http/errorMapper';
import { EmptyState } from './EmptyState';
import { ErrorBanner } from './ErrorBanner';
import { fmtDate } from './format';
import { MaskedValue } from './MaskedValue';
import { Pager, type PageChange } from './Pager';
import { StatusChip } from './StatusChip';

export interface Column<T> {
  key: string;
  header: string;
  type?: 'text' | 'status' | 'date' | 'mono' | 'masked' | 'bool';
  /** Optional value extractor; defaults to row[key]. */
  value?: (row: T) => unknown;
  /** Optional in-app link target for the cell. */
  link?: (row: T) => string | null;
}

export interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[];
  loading?: boolean;
  error?: NormalizedError | null;
  caption?: string;
  emptyTitle?: string;
  emptyMessage?: string;
  total?: number;
  page?: number;
  size?: number;
  rowActions?: (row: T) => ReactNode;
  onPageChange?: (c: PageChange) => void;
  onRetry?: () => void;
}

const str = (v: unknown): string => (v === null || v === undefined ? '' : String(v));

/**
 * Generic table with loading, error and empty states and an optional pager.
 * Cell values are always rendered as text (never as HTML).
 */
export function DataTable<T>({
  columns, rows, loading = false, error = null, caption = 'Data table', emptyTitle = 'No results',
  emptyMessage = 'Nothing matches the current filters.', total = 0, page = 0, size = 20, rowActions, onPageChange, onRetry,
}: DataTableProps<T>) {
  const cell = (c: Column<T>, row: T): ReactNode => {
    const v = c.value ? c.value(row) : (row as Record<string, unknown>)[c.key];
    const link = c.link?.(row) ?? null;
    switch (c.type) {
      case 'status': return <StatusChip status={str(v)} />;
      case 'date': return v ? fmtDate(str(v)) : '—';
      case 'masked': return <MaskedValue value={str(v)} />;
      case 'bool': return v ? 'Yes' : 'No';
      default: {
        const text = v === null || v === undefined || v === '' ? '—' : str(v);
        if (link) {
          return <Link to={link} className={c.type === 'mono' ? 'mono' : undefined}>{text}</Link>;
        }
        return c.type === 'mono' ? <span className="mono">{text}</span> : text;
      }
    }
  };

  return (
    <div className="data-table">
      <ErrorBanner error={error} onRetry={onRetry} />
      {loading ? <div className="progress" role="progressbar" aria-label="Loading data" /> : null}
      {!error && !loading && rows.length === 0 ? <EmptyState title={emptyTitle} message={emptyMessage} /> : null}
      {!error && rows.length > 0 ? (
        <div className="scroll" tabIndex={0} role="region" aria-label={caption}>
          <table className="simple">
            <caption className="sr-only">{caption}</caption>
            <thead>
              <tr>
                {columns.map((c) => <th key={c.key} scope="col">{c.header}</th>)}
                {rowActions ? <th scope="col">Actions</th> : null}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr key={i}>
                  {columns.map((c) => <td key={c.key}>{cell(c, row)}</td>)}
                  {rowActions ? <td className="actions-cell">{rowActions(row)}</td> : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {!error && total > 0 && onPageChange ? <Pager total={total} page={page} size={size} onChange={onPageChange} /> : null}
    </div>
  );
}
