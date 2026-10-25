export interface ChartDatum {
  label: string;
  value: number;
}

export interface DonutSlice {
  label: string;
  value: number;
  color: string;
  path: string;
  percent: number;
}

export const PALETTE = ['#1a5fb4', '#26a269', '#c64600', '#813d9c', '#a51d2d', '#5e5c64'];

/** SVG arc path for a donut slice between two angles (radians, 0 = top, clockwise). */
export function arcPath(cx: number, cy: number, rOuter: number, rInner: number, a0: number, a1: number): string {
  const p = (r: number, a: number): string => `${(cx + r * Math.sin(a)).toFixed(2)} ${(cy - r * Math.cos(a)).toFixed(2)}`;
  const large = a1 - a0 > Math.PI ? 1 : 0;
  return `M ${p(rOuter, a0)} A ${rOuter} ${rOuter} 0 ${large} 1 ${p(rOuter, a1)} L ${p(rInner, a1)} A ${rInner} ${rInner} 0 ${large} 0 ${p(rInner, a0)} Z`;
}

export function buildSlices(data: ChartDatum[], cx = 90, cy = 90, ro = 80, ri = 48): DonutSlice[] {
  const total = data.reduce((s, d) => s + Math.max(0, d.value), 0);
  if (total <= 0) {
    return [];
  }
  let angle = 0;
  return data
    .filter((d) => d.value > 0)
    .map((d, i) => {
      const frac = d.value / total;
      // A full circle cannot be drawn as one arc; cap just below 2*PI.
      const sweep = Math.min(frac * 2 * Math.PI, 2 * Math.PI - 0.0001);
      const slice: DonutSlice = {
        label: d.label, value: d.value, color: PALETTE[i % PALETTE.length],
        path: arcPath(cx, cy, ro, ri, angle, angle + sweep), percent: Math.round(frac * 1000) / 10,
      };
      angle += sweep;
      return slice;
    });
}

/** Horizontal bar chart in plain SVG with a data-table fallback for assistive tech. */
export function BarChart({ data, title = 'Bar chart', color = PALETTE[0] }: { data: ChartDatum[]; title?: string; color?: string }) {
  const max = Math.max(1, ...data.map((x) => x.value));
  const bars = data.map((x, i) => ({ ...x, y: 8 + i * 28, w: Math.round((x.value / max) * 300) }));
  return (
    <>
      <svg viewBox={`0 0 520 ${16 + data.length * 28}`} role="img" aria-label={title} className="chart">
        <title>{title}</title>
        {bars.map((b) => (
          <g key={b.label}>
            <text x={140} y={b.y + 15} textAnchor="end" className="lbl">{b.label}</text>
            <rect x={150} y={b.y} width={b.w} height={20} rx={3} fill={color} />
            <text x={156 + b.w} y={b.y + 15} className="val">{b.value}</text>
          </g>
        ))}
      </svg>
      <table className="sr-only">
        <caption>{title}</caption>
        <tbody>{data.map((d) => <tr key={d.label}><th scope="row">{d.label}</th><td>{d.value}</td></tr>)}</tbody>
      </table>
    </>
  );
}

/** Donut chart in plain SVG with legend. */
export function DonutChart({ data, title = 'Donut chart' }: { data: ChartDatum[]; title?: string }) {
  const slices = buildSlices(data);
  const total = data.reduce((s, d) => s + d.value, 0);
  return (
    <div className="donut-wrap">
      <svg viewBox="0 0 180 180" role="img" aria-label={title} className="donut">
        <title>{title}</title>
        {slices.map((s) => <path key={s.label} d={s.path} fill={s.color} />)}
        <text x={90} y={95} textAnchor="middle" className="center">{total}</text>
      </svg>
      <ul className="legend">
        {slices.map((s) => (
          <li key={s.label}><span className="sw" style={{ background: s.color }} />{s.label}: <strong>{s.value}</strong> ({s.percent}%)</li>
        ))}
        {slices.length === 0 ? <li className="muted">No data</li> : null}
      </ul>
    </div>
  );
}
