import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([])] }).compileComponents();
  });

  it('creates the root component with a router outlet', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
    expect(fixture.nativeElement.querySelector('router-outlet')).not.toBeNull();
  });

  it('declares every contract page route, lazily loaded, behind the auth guard', () => {
    const shell = routes.find((r) => r.path === '')!;
    expect(shell.canActivate?.length).toBe(1);
    const paths = (shell.children ?? []).map((c) => c.path);
    for (const p of ['dashboard', 'messages', 'messages/new', 'messages/:id', 'templates', 'templates/:id', 'preferences', 'bulk-jobs',
      'bulk-jobs/:id', 'providers', 'queues', 'failed', 'dead-letters', 'delivery-events', 'audit', 'admin']) {
      expect(paths).withContext(p).toContain(p);
    }
    expect(routes.some((r) => r.path === 'login')).toBeTrue();
    for (const c of shell.children ?? []) {
      if (c.path !== '' && c.redirectTo === undefined) {
        expect(c.loadComponent).withContext(String(c.path)).toBeDefined();
      }
    }
  });

  it('puts role guards on privileged routes (UX only)', () => {
    const children = routes.find((r) => r.path === '')!.children ?? [];
    for (const p of ['admin', 'queues', 'audit', 'dead-letters', 'messages/new']) {
      const r = children.find((c) => c.path === p)!;
      expect(r.canActivate?.length).withContext(p).toBe(1);
      expect(r.data?.['roles']).withContext(p).toBeTruthy();
    }
  });
});
