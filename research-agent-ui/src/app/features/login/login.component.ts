import { Component, inject, signal } from '@angular/core';
import { trigger, transition, style, animate } from '@angular/animations';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { Router, ActivatedRoute } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../../core/services/auth.service';

/**
 * Unauthenticated entry point: sign-in form with a registration toggle (Phase 1 ships
 * POST /api/auth/register with 201 auto-login, so first-time users need no curl).
 * Backend errors arrive as {"error": "..."} and are rendered inline. Arriving with
 * ?reason=expired (sent by the auth interceptor) shows a session-expired banner.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule
  ],
  animations: [
    trigger('fadeIn', [
      transition(':enter', [style({ opacity: 0, transform: 'translateY(-8px)' }), animate('300ms ease-out', style({ opacity: 1, transform: 'translateY(0)' }))]),
    ]),
  ],
  template: `
    <div class="login-container">
      <mat-card class="login-card" [@fadeIn]>
        <div class="card-header">
          <h1>{{ mode() === 'login' ? 'Sign in' : 'Create account' }}</h1>
          <p>
            {{ mode() === 'login'
               ? 'Sign in to continue your research.'
               : 'Pick a username and password — you will be signed in right away.' }}
          </p>
        </div>

        @if (showExpiredBanner) {
          <div class="expired-banner">Session expired — please sign in again.</div>
        }

        <mat-card-content>
          <form [formGroup]="form" (ngSubmit)="onSubmit()">

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Username</mat-label>
              <input
                matInput
                formControlName="username"
                autocomplete="username"
                placeholder="3-32 characters: letters, digits, underscore"
              />
              @if (form.get('username')?.hasError('required')) {
                <mat-error>Username is required</mat-error>
              }
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Password</mat-label>
              <input
                matInput
                type="password"
                formControlName="password"
                [autocomplete]="mode() === 'login' ? 'current-password' : 'new-password'"
              />
              @if (form.get('password')?.hasError('required')) {
                <mat-error>Password is required</mat-error>
              } @else if (form.get('password')?.hasError('minlength')) {
                <mat-error>Password must be at least 8 characters</mat-error>
              }
            </mat-form-field>

            @if (error()) {
              <div class="form-error">{{ error() }}</div>
            }

            <button
              mat-raised-button
              color="primary"
              type="submit"
              [disabled]="!form.valid || isLoading()"
              class="submit-btn"
            >
              @if (isLoading()) {
                <span class="spinner"></span>
                {{ mode() === 'login' ? 'Signing in...' : 'Creating account...' }}
              } @else {
                {{ mode() === 'login' ? 'Sign in' : 'Create account' }}
              }
            </button>

            <p class="mode-toggle">
              @if (mode() === 'login') {
                Don't have an account?
                <button type="button" class="link-btn" (click)="switchMode('register')">Create one</button>
              } @else {
                Already have an account?
                <button type="button" class="link-btn" (click)="switchMode('login')">Sign in</button>
              }
            </p>

          </form>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .login-container {
      display: flex;
      justify-content: center;
      padding: 48px 24px;
    }

    .login-card {
      width: 100%;
      max-width: 440px;
      border-radius: 16px !important;
      box-shadow: 0 4px 24px rgba(99, 102, 241, 0.08) !important;
      transition: box-shadow 0.3s ease;
    }

    .login-card:hover {
      box-shadow: 0 8px 32px rgba(99, 102, 241, 0.12) !important;
    }

    .card-header { padding: 24px 24px 0; }
    .card-header h1 { margin: 0 0 8px; font-size: 1.75rem; font-weight: 800; color: #0f0f23 !important; }
    .card-header p { margin: 0; color: #4a4a6a !important; font-size: 0.95rem; line-height: 1.5; }

    mat-card-content { padding-top: 16px; }
    .full-width { width: 100%; margin-bottom: 8px; }

    .expired-banner {
      margin: 12px 24px 0;
      padding: 10px 14px;
      border-radius: 10px;
      background: rgba(255, 152, 0, 0.12);
      color: #b26a00 !important;
      font-size: 0.9rem;
      font-weight: 500;
    }

    .form-error {
      margin: 4px 0 12px;
      padding: 10px 14px;
      border-radius: 10px;
      background: rgba(244, 67, 54, 0.1);
      color: #c62828 !important;
      font-size: 0.9rem;
    }

    button[mat-raised-button] {
      border-radius: 10px !important;
      font-weight: 600;
      text-transform: none;
      letter-spacing: 0.5px;
      padding: 0 24px;
      height: 48px;
      width: 100%;
      transition: all 0.2s ease;
    }

    button[mat-raised-button]:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 6px 16px rgba(99, 102, 241, 0.3);
    }

    .spinner {
      display: inline-block;
      width: 18px;
      height: 18px;
      border: 2px solid rgba(255, 255, 255, 0.3);
      border-top-color: #fff;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
      margin-right: 8px;
    }
    @keyframes spin { to { transform: rotate(360deg); } }

    .mode-toggle {
      margin: 20px 0 0;
      font-size: 0.9rem;
      color: #4a4a6a !important;
      text-align: center;
    }

    .link-btn {
      background: none;
      border: none;
      padding: 0;
      margin-left: 4px;
      color: #6366f1 !important;
      font-size: inherit;
      font-weight: 600;
      cursor: pointer;
      text-decoration: underline;
    }

    /* Dark mode */
    @media (prefers-color-scheme: dark) {
      .card-header h1 { color: #e0e0e0; }
      .mode-toggle { color: #9e9e9e; }
    }
  `]
})
export class LoginComponent {

  private fb = inject(FormBuilder);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private auth = inject(AuthService);

  mode = signal<'login' | 'register'>('login');
  isLoading = signal(false);
  error = signal<string | null>(null);
  readonly showExpiredBanner: boolean;

  form = this.fb.group({
    username: ['', Validators.required],
    password: ['', [Validators.required, Validators.minLength(8)]]
  });

  constructor() {
    this.showExpiredBanner = this.route.snapshot.queryParamMap.get('reason') === 'expired';

    // If a session is valid while the form is up (already authenticated, or the boot-time
    // restore just finished), a signed-in user should never see the login screen.
    // restoreSession() returns a cached promise, so this never fires an extra request.
    void this.auth.restoreSession().then(() => {
      if (this.auth.authStatus() === 'authenticated') {
        this.router.navigate(['/research/new']);
      }
    });
  }

  switchMode(mode: 'login' | 'register'): void {
    this.mode.set(mode);
    this.error.set(null);
  }

  onSubmit(): void {
    if (!this.form.valid || this.isLoading()) {
      return;
    }

    const username = this.form.value.username!;
    const password = this.form.value.password!;
    this.isLoading.set(true);
    this.error.set(null);

    const request$ = this.mode() === 'login'
      ? this.auth.login(username, password)
      : this.auth.register(username, password);

    request$.subscribe({
      next: (response) => {
        // Both login (200) and register (201) return {token, username} — auto-login.
        this.auth.completeLogin(response);
        this.router.navigate(['/research/new']);
      },
      error: (err: unknown) => {
        console.error('[LoginComponent] Auth request failed:', err);
        this.error.set(this.extractError(err));
        this.isLoading.set(false);
      }
    });
  }

  /** Backend auth failures share one shape: {"error": "..."}. Render it verbatim. */
  private extractError(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      // Angular 18: the parsed response body lives on `error`.
      const body = err.error as { error?: string } | null;
      if (body?.error) {
        return body.error;
      }
      if (err.status === 0) {
        return 'Cannot reach the server';
      }
      return `Request failed (${err.status})`;
    }
    return 'Something went wrong';
  }
}
