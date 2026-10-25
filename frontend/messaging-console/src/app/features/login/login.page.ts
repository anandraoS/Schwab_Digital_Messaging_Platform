import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { AuthService, PERSONAS } from '../../core/auth/auth.service';
import { NormalizedError, toNormalizedError } from '../../core/http/error-mapper';
import { Persona } from '../../core/models/api.models';
import { ErrorBannerComponent } from '../../shared/error-banner';

/** Dev persona picker. Calls POST /dev/token (local/test profiles only on the backend). */
@Component({
  selector: 'app-login-page',
  imports: [MatButtonModule, ErrorBannerComponent],
  template: `
    <main class="wrap">
      <h1>Messaging Operations Console</h1>
      <p class="muted">Development sign-in: pick a persona. A signed dev token is requested from <code>/dev/token</code>
        and kept in memory and sessionStorage. This page does not exist in production.</p>
      @if (expired()) { <p role="status" class="muted">Your session expired. Please sign in again.</p> }
      <app-error-banner [error]="error()" [retryable]="false" />
      <div class="personas" role="list">
        @for (p of personas; track p.id) {
          <button type="button" class="persona" role="listitem" mat-stroked-button [disabled]="busy()" (click)="signIn(p)"
                  [attr.data-persona]="p.id">
            <span class="label">{{ p.label }}</span>
            <span class="desc muted">{{ p.description }}</span>
            <span class="mono">{{ p.request.roles.join(', ') }} · {{ p.request.tenant }}</span>
          </button>
        }
      </div>
    </main>
  `,
  styles: `
    .wrap { max-width: 860px; margin: 40px auto; padding: 0 16px; }
    .personas { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; margin-top: 16px; }
    .persona { height: auto !important; padding: 12px !important; display: flex; flex-direction: column; align-items: flex-start; text-align: left; gap: 4px; white-space: normal; }
    .label { font-weight: 600; }
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly personas = PERSONAS;
  readonly busy = signal(false);
  readonly error = signal<NormalizedError | null>(null);
  readonly expired = () => this.route.snapshot.queryParamMap.get('reason') === 'expired';

  signIn(p: Persona): void {
    this.busy.set(true);
    this.error.set(null);
    this.auth.login(p.request).subscribe({
      next: () => {
        this.busy.set(false);
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        // Only allow same-app relative redirects (avoid open redirects).
        const safe = returnUrl && returnUrl.startsWith('/') && !returnUrl.startsWith('//') ? returnUrl : '/dashboard';
        void this.router.navigateByUrl(safe);
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(toNormalizedError(e));
      },
    });
  }
}
