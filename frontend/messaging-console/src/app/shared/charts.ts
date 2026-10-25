import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

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
@Component({
  selector: 'app-bar-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.viewBox]="'0 0 520 ' + height()" role="img" [attr.aria-label]="title()" class="chart">
      <title>{{ title() }}</title>
      @for (b of bars(); track b.label) {
        <text [attr.x]="140" [attr.y]="b.y + 15" text-anchor="end" class="lbl">{{ b.label }}</text>
        <rect [attr.x]="150" [attr.y]="b.y" [attr.width]="b.w" height="20" rx="3" [attr.fill]="b.color"></rect>
        <text [attr.x]="156 + b.w" [attr.y]="b.y + 15" class="val">{{ b.value }}</text>
      }
    </svg>
    <table class="sr-only">
      <caption>{{ title() }}</caption>
      <tbody>@for (d of data(); track d.label) { <tr><th scope="row">{{ d.label }}</th><td>{{ d.value }}</td></tr> }</tbody>
    </table>
  `,
  styles: `.chart { width: 100%; height: auto; max-height: 460px; } .lbl { font-size: 12px; fill: #333; } .val { font-size: 12px; fill: #111; font-weight: 600; }`,
})
export class BarChartComponent {
  readonly data = input<ChartDatum[]>([]);
  readonly title = input('Bar chart');
  readonly color = input(PALETTE[0]);
  readonly bars = computed(() => {
    const d = this.data();
    const max = Math.max(1, ...d.map((x) => x.value));
    return d.map((x, i) => ({ label: x.label, value: x.value, y: 8 + i * 28, w: Math.round((x.value / max) * 300), color: this.color() }));
  });
  readonly height = computed(() => 16 + this.data().length * 28);
}

/** Donut chart in plain SVG with legend. */
@Component({
  selector: 'app-donut-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <svg viewBox="0 0 180 180" role="img" [attr.aria-label]="title()" class="donut">
        <title>{{ title() }}</title>
        @for (s of slices(); track s.label) { <path [attr.d]="s.path" [attr.fill]="s.color"></path> }
        <text x="90" y="95" text-anchor="middle" class="center">{{ total() }}</text>
      </svg>
      <ul class="legend">
        @for (s of slices(); track s.label) {
          <li><span class="sw" [style.background]="s.color"></span>{{ s.label }}: <strong>{{ s.value }}</strong> ({{ s.percent }}%)</li>
        }
        @if (!slices().length) { <li class="muted">No data</li> }
      </ul>
    </div>
  `,
  styles: `.wrap { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; } .donut { width: 160px; height: 160px; }
    .center { font-size: 22px; font-weight: 700; fill: #111; } .legend { list-style: none; padding: 0; margin: 0; }
    .sw { display: inline-block; width: 12px; height: 12px; margin-right: 6px; border-radius: 2px; }`,
})
export class DonutChartComponent {
  readonly data = input<ChartDatum[]>([]);
  readonly title = input('Donut chart');
  readonly slices = computed(() => buildSlices(this.data()));
  readonly total = computed(() => this.data().reduce((s, d) => s + d.value, 0));
}
