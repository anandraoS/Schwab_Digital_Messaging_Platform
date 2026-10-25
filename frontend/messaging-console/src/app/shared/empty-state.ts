import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty" role="status">
      <strong>{{ title() }}</strong>
      @if (message()) { <p class="muted">{{ message() }}</p> }
      <ng-content />
    </div>
  `,
  styles: `.empty { text-align: center; padding: 32px 16px; border: 1px dashed var(--mat-sys-outline); border-radius: 12px; }
    .empty p { margin: 8px 0 0; }`,
})
export class EmptyStateComponent {
  readonly title = input('Nothing to show');
  readonly message = input('');
}
