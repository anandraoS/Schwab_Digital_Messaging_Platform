import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { NormalizedError } from '../core/http/error-mapper';

/** Renders a normalized API error with actionable text, field details and the correlation id. */
@Component({
  selector: 'app-error-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  template: `
    @if (error(); as e) {
      <div class="banner" role="alert">
        <div class="head">
          <strong>{{ e.message }}</strong>
          @if (retryable()) { <button mat-stroked-button type="button" (click)="retry.emit()">Retry</button> }
        </div>
        @if (e.serverMessage && e.serverMessage !== e.message) { <div class="srv">{{ e.serverMessage }}</div> }
        @if (e.details.length) {
          <ul class="details">
            @for (d of e.details; track $index) { <li><code>{{ d.field }}</code>: {{ d.message }}</li> }
          </ul>
        }
        <div class="meta mono">
          code={{ e.code }} · status={{ e.status }}
          @if (e.correlationId) { · correlationId={{ e.correlationId }} }
          @if (e.retryAfterSeconds !== undefined) { · retry after {{ e.retryAfterSeconds }}s }
        </div>
      </div>
    }
  `,
  styles: `
    .banner { border: 1px solid #b71c1c; background: #fdecea; color: #5f1111; border-radius: 8px; padding: 10px 14px; margin: 8px 0; }
    .head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
    .details { margin: 6px 0 0 18px; padding: 0; }
    .srv { margin-top: 4px; }
    .meta { margin-top: 6px; opacity: .85; word-break: break-all; }
  `,
})
export class ErrorBannerComponent {
  readonly error = input<NormalizedError | null>(null);
  readonly retryable = input(true);
  readonly retry = output<void>();
}
