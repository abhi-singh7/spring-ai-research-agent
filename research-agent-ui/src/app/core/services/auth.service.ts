import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface AuthUser {
  username: string;
  role: string;
}

/** Shared response shape of POST /api/auth/login and POST /api/auth/register (201 auto-login). */
export interface AuthTokenResponse {
  token: string;
  username: string;
}

/**
 * Owns the JWT (localStorage key `ra-auth-token`) and the app-wide auth state.
 *
 * State machine: boot -> 'checking' -> restoreSession() -> 'authenticated' | 'anonymous'.
 * `restoreSession()` returns a single cached promise so the boot call (AppComponent)
 * and route guards share exactly one GET /api/auth/me request.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {

  static readonly TOKEN_KEY = 'ra-auth-token';

  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiUrl;

  readonly authStatus = signal<'checking' | 'authenticated' | 'anonymous'>('checking');
  readonly currentUser = signal<AuthUser | null>(null);

  /** True when boot-time restore rejected a previously stored token — the guard then
   * redirects to /login?reason=expired so the user sees why they were signed out. */
  readonly restoreExpired = signal(false);

  private restorePromise: Promise<void> | null = null;

  getToken(): string | null {
    return localStorage.getItem(AuthService.TOKEN_KEY);
  }

  login(username: string, password: string): Observable<AuthTokenResponse> {
    return this.http.post<AuthTokenResponse>(`${this.baseUrl}/api/auth/login`, { username, password });
  }

  register(username: string, password: string): Observable<AuthTokenResponse> {
    return this.http.post<AuthTokenResponse>(`${this.baseUrl}/api/auth/register`, { username, password });
  }

  /** Store a token returned by login/register and flip to the authenticated state. */
  completeLogin(response: AuthTokenResponse): void {
    localStorage.setItem(AuthService.TOKEN_KEY, response.token);
    // Login/register responses carry no role; Phase 2 has no ADMIN-facing UI, so USER is correct.
    this.currentUser.set({ username: response.username, role: 'USER' });
    this.authStatus.set('authenticated');
  }

  /** Clear the stored token and drop to anonymous (does not navigate — caller decides). */
  logout(): void {
    localStorage.removeItem(AuthService.TOKEN_KEY);
    this.currentUser.set(null);
    this.authStatus.set('anonymous');
    this.restoreExpired.set(false);
  }

  /**
   * Validate any stored token via GET /api/auth/me before routes render.
   * Missing token -> anonymous immediately; invalid/rejected token -> discarded + anonymous.
   * Idempotent: repeated callers await the same cached promise.
   */
  restoreSession(): Promise<void> {
    if (this.restorePromise) {
      return this.restorePromise;
    }

    const token = this.getToken();
    if (!token) {
      this.authStatus.set('anonymous');
      this.restorePromise = Promise.resolve();
      return this.restorePromise;
    }

    this.restorePromise = new Promise<void>(resolve => {
      this.http.get<AuthUser>(`${this.baseUrl}/api/auth/me`, {
        headers: { Authorization: `Bearer ${token}` }
      }).subscribe({
        next: me => {
          this.currentUser.set({ username: me.username, role: me.role });
          this.authStatus.set('authenticated');
          resolve();
        },
        error: (err: HttpErrorResponse) => {
          console.warn('[AuthService] Stored token rejected by /api/auth/me — clearing session.');
          localStorage.removeItem(AuthService.TOKEN_KEY);
          this.currentUser.set(null);
          this.authStatus.set('anonymous');
          // A stored token that no longer validates is, by definition, an expired session.
          this.restoreExpired.set(true);
          resolve();
        }
      });
    });

    return this.restorePromise;
  }
}
