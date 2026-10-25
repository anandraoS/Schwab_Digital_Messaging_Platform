import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, computed, inject, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { NAV_ITEMS } from '../core/auth/access';
import { AuthService } from '../core/auth/auth.service';
import { LoadingService } from '../core/http/loading.service';

/**
 * Role-aware navigation shell. Hiding nav entries is a UX nicety only; the backend enforces
 * authorization on every request.
 */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive, FormsModule, MatSidenavModule, MatToolbarModule, MatButtonModule,
    MatProgressBarModule, MatFormFieldModule, MatInputModule,
  ],
  template: `
    <a class="skip" href="#main-content">Skip to content</a>
    <mat-toolbar class="bar">
      <button mat-button type="button" class="menu-btn" (click)="sidenav.toggle()"
              [attr.aria-expanded]="sidenav.opened" aria-controls="side-nav">Menu</button>
      <span class="brand">Messaging Operations Console</span>
      <span class="grow"></span>
      @if (auth.roles().includes('PLATFORM_ADMIN')) {
        <mat-form-field class="scope" subscriptSizing="dynamic">
          <mat-label>Tenant scope (X-Tenant-Id)</mat-label>
          <input matInput [ngModel]="auth.tenantScope()" (ngModelChange)="auth.tenantScope.set($event)" [placeholder]="'reads: all tenants, writes: ' + auth.defaultWriteTenant()" />
        </mat-form-field>
      }
      <span class="who" [attr.aria-label]="'Signed in as ' + auth.subject()">
        {{ auth.subject() }} <small>({{ auth.roles().join(', ') }} · {{ auth.tenant() }})</small>
      </span>
      <button mat-stroked-button type="button" (click)="logout()">Sign out</button>
    </mat-toolbar>
    @if (loading.active()) {
      <mat-progress-bar class="global-loading" mode="indeterminate" aria-label="Loading" />
    }
    <mat-sidenav-container class="container">
      <mat-sidenav #sidenav id="side-nav" [mode]="isSmall() ? 'over' : 'side'" [opened]="!isSmall()" role="navigation" aria-label="Main">
        <nav class="nav">
          @for (item of items(); track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: item.path === '/messages' }"
               (click)="closeIfSmall()">{{ item.label }}</a>
          }
        </nav>
      </mat-sidenav>
      <mat-sidenav-content>
        <main id="main-content" tabindex="-1">
          <router-outlet />
        </main>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
  styles: `
    :host { display: flex; flex-direction: column; height: 100vh; }
    .skip { position: absolute; left: -999px; top: 0; background: #fff; padding: 8px; z-index: 1000; }
    .skip:focus { left: 8px; top: 8px; }
    .bar { gap: 12px; flex: 0 0 auto; background: var(--mat-sys-primary); color: var(--mat-sys-on-primary);
      --mat-toolbar-container-background-color: var(--mat-sys-primary); --mat-toolbar-container-text-color: var(--mat-sys-on-primary); }
    .bar button { color: inherit; }
    .brand { font-weight: 600; }
    .grow { flex: 1 1 auto; }
    .who small { opacity: .9; }
    .scope { width: 220px; --mat-form-field-container-height: 36px; }
    .global-loading { position: fixed; top: 0; left: 0; right: 0; z-index: 2000; }
    .container { flex: 1 1 auto; }
    mat-sidenav { width: 230px; }
    .nav { display: flex; flex-direction: column; padding: 8px; }
    .nav a { padding: 8px 12px; border-radius: 8px; text-decoration: none; color: var(--mat-sys-on-surface); }
    .nav a:hover { background: var(--mat-sys-surface-container-high); }
    .nav a.active { background: var(--mat-sys-secondary-container); font-weight: 600; }
    @media (min-width: 960px) { .menu-btn { display: none; } }
    @media (max-width: 700px) { .who { display: none; } .scope { display: none; } }
  `,
})
export class ShellComponent {
  readonly auth = inject(AuthService);
  readonly loading = inject(LoadingService);
  private readonly router = inject(Router);
  private readonly bp = inject(BreakpointObserver);
  private readonly nav = viewChild.required<MatSidenav>('sidenav');

  readonly isSmall = toSignal(this.bp.observe('(max-width: 959px)').pipe(map((r) => r.matches)), { initialValue: false });
  readonly items = computed(() => NAV_ITEMS.filter((i) => this.auth.hasAnyRole(i.roles)));

  closeIfSmall(): void {
    if (this.isSmall()) {
      void this.nav().close();
    }
  }

  logout(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
