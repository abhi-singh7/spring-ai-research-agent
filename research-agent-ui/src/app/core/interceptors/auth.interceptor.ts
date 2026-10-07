import { Injectable, inject } from '@angular/core';
import { HttpErrorResponse, HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { ResearchService } from '../services/research.service';

/**
 * Attaches `Authorization: Bearer <jwt>` to every /api request while a token is stored,
 * and enforces the session-expiry rule: a 401 from any NON-auth endpoint means the session
 * is over -> clear the token, stop active streaming/polling, redirect to /login?reason=expired.
 *
 * 401s from /api/auth/** are exempt — there they are normal business errors (wrong password)
 * that the login form renders inline.
 */
@Injectable()
export class AuthHttpInterceptor implements HttpInterceptor {

  private readonly auth = inject(AuthService);
  private readonly research = inject(ResearchService);
  private readonly router = inject(Router);

  intercept(req: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    const isApi = req.url.includes('/api/');
    const token = this.auth.getToken();
    const request = (isApi && token)
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

    return next.handle(request).pipe(
      tap({
        error: (err: unknown) => {
          if (
            err instanceof HttpErrorResponse &&
            err.status === 401 &&
            isApi &&
            !req.url.includes('/api/auth/')
          ) {
            this.handleSessionExpired();
          }
        }
      })
    );
  }

  private handleSessionExpired(): void {
    // A concurrent request may have already handled the same expiry — avoid double navigation.
    if (this.auth.authStatus() === 'anonymous') {
      return;
    }
    this.auth.logout();
    this.research.clearActiveWork();
    this.router.navigate(['/login'], { queryParams: { reason: 'expired' } });
  }
}
