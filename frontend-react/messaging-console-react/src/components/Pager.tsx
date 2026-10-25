export interface PageChange {
  page: number;
  size: number;
}

const SIZES = [10, 20, 50, 100];

/** Zero-based pager like the API. */
export function Pager({ total, page, size, onChange }: { total: number; page: number; size: number; onChange: (c: PageChange) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  return (
    <nav className="pager" aria-label="Select page">
      <label>
        Rows per page{' '}
        <select value={size} onChange={(e) => onChange({ page: 0, size: Number(e.target.value) })}>
          {SIZES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </label>
      <span role="status" data-testid="pager-range">{from}–{to} of {total}</span>
      <button type="button" className="btn" aria-label="First page" disabled={page <= 0} onClick={() => onChange({ page: 0, size })}>«</button>
      <button type="button" className="btn" aria-label="Previous page" disabled={page <= 0} onClick={() => onChange({ page: page - 1, size })}>‹</button>
      <button type="button" className="btn" aria-label="Next page" disabled={page >= pages - 1} onClick={() => onChange({ page: page + 1, size })}>›</button>
      <button type="button" className="btn" aria-label="Last page" disabled={page >= pages - 1} onClick={() => onChange({ page: pages - 1, size })}>»</button>
    </nav>
  );
}
