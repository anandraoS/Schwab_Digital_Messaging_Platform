import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from '../models/api.models';
import { AuthService } from './auth.service';

/**
 * NOTE: guards are a UX convenience ONLY. They avoid showing pages the user cannot use.
 * Real authorization (roles, tenant isolation) is enforced by the backend on every request.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.isAuthenticated()
    ? true
    : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/** Route data: `{ roles: Role[] }`. UX only (see above). */
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }
  const roles = route.data['roles'] as Role[] | undefined;
  return auth.hasAnyRole(roles) ? true : router.createUrlTree(['/forbidden']);
};
