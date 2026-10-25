import type { HistoryEntry } from '../core/models';
import { fmtDate } from './format';
import { StatusChip } from './StatusChip';

/** Target status of a lifecycle transition (`detail.to`), or the provider receipt type. */
export function statusOf(e: HistoryEntry): string | null {
  const to = e.detail?.['to'];
  if (typeof to === 'string' && to) {
    return to;
  }
  return e.kind === 'DELIVERY_EVENT' ? e.type : null;
}

export function actorOf(e: HistoryEntry): string | null {
  const a = e.detail?.['actor'];
  return typeof a === 'string' && a ? a : null;
}

/** Remaining detail fields as "key: value" pairs (actor and target status are shown elsewhere). */
export function detailText(e: HistoryEntry): string {
  const d = e.detail;
  if (!d) {
    return '';
  }
  return Object.entries(d)
    .filter(([k, v]) => k !== 'actor' && k !== 'to' && v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(' · ');
}

/** Vertical lifecycle timeline, ordered oldest-first. All text is rendered as text (never HTML). */
export function Timeline({ entries }: { entries: HistoryEntry[] }) {
  const sorted = [...entries].sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime());
  return (
    <ol className="tl" aria-label="Message lifecycle timeline">
      {sorted.map((e, i) => {
        const st = statusOf(e);
        const actor = actorOf(e);
        const text = detailText(e);
        return (
          <li key={i} className="item" data-kind={e.kind}>
            <span className="dot" aria-hidden="true" />
            <div className="body">
              <div className="row">
                <strong>{e.summary}</strong>
                {st ? <StatusChip status={st} /> : null}
                <span className="kind muted">{e.kind}</span>
              </div>
              <time className="muted" dateTime={e.at}>{fmtDate(e.at, true)}</time>
              {actor ? <span className="muted"> · {actor}</span> : null}
              {text ? <div className="detail">{text}</div> : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
