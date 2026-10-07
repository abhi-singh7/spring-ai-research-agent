import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Guards all research routes. While the boot-time session check is still running
 * ('checking') it awaits the cached restoreSession() promise instead of flashing a
 * redirect; anonymous users are sent to /login, authenticated users pass through.
 */
export const authGuard: CanActivateFn = async () => {
  // Both dependencies must be captured BEFORE the await — inject() is only valid
  // synchronously inside the guard's injection context.
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.authStatus() === 'checking') {
    await auth.restoreSession(); // cached — at most one GET /api/auth/me per boot
  }

  if (auth.authStatus() === 'authenticated') {
    return true;
  }

  // If a stored token was just rejected at boot, tell the login screen why.
  return router.createUrlTree(['/login'], {
    queryParams: auth.restoreExpired() ? { reason: 'expired' } : {}
  });
};
