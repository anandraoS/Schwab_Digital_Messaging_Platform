const pad = (n: number, w = 2): string => String(n).padStart(w, '0');

/** `yyyy-MM-dd HH:mm:ss` in local time ('' for missing/invalid). Pass `ms` for `.SSS`. */
export function fmtDate(iso: string | null | undefined, ms = false): string {
  if (!iso) {
    return '';
  }
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return String(iso);
  }
  const base = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  return ms ? `${base}.${pad(d.getMilliseconds(), 3)}` : base;
}

/** Date or an em dash. */
export function dateOrDash(iso: string | null | undefined): string {
  return iso ? fmtDate(iso) : '—';
}

/** Human-readable age, e.g. 3725 -> "1h 2m 5s". */
export function formatAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || seconds < 0) {
    return '—';
  }
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return [h ? `${h}h` : '', h || m ? `${m}m` : '', `${r}s`].filter(Boolean).join(' ');
}

/** 0..1 ratio as a percentage with one decimal ('—' for missing). */
export function pct(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : `${Math.round(v * 1000) / 10}%`;
}
