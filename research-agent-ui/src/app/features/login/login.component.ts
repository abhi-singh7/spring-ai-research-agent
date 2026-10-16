import { Component, inject, signal } from '@angular/core';
import { trigger, transition, style, animate } from '@angular/animations';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
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
    MatButtonModule,
    MatProgressSpinnerModule
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
              type="submit"
              [disabled]="!form.valid || isLoading()"
              class="mat-primary submit-btn"
            >
              @if (isLoading()) {
                <mat-progress-spinner class="btn-spinner" mode="indeterminate" diameter="18" strokeWidth="2" />
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
      /* Material card API — shape + elevation tokens (scheme-aware shadows, no
         hand-drawn box-shadow, no !important). */
      --mdc-elevated-card-container-shape: 16px;
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-2);
    }

    .login-card:hover {
      --mdc-elevated-card-container-elevation: var(--mat-app-elevation-shadow-level-5);
    }

    .card-header { padding: 24px 24px 0; }
    .card-header h1 { margin: 0 0 8px; font-size: 1.75rem; font-weight: 800; color: var(--rp-on-surface) !important; }
    .card-header p { margin: 0; color: var(--rp-on-surface-variant) !important; font-size: 0.95rem; line-height: 1.5; }

    mat-card-content { padding-top: 16px; }
    .full-width { width: 100%; margin-bottom: 8px; }

    .expired-banner {
      margin: 12px 24px 0;
      padding: 10px 14px;
      border-radius: 10px;
      background: var(--rp-warning-soft);
      color: var(--rp-warning) !important;
      font-size: 0.9rem;
      font-weight: 500;
    }

    .form-error {
      margin: 4px 0 12px;
      padding: 10px 14px;
      border-radius: 10px;
      background: var(--rp-error-soft);
      color: var(--rp-error) !important;
      font-size: 0.9rem;
    }

    button[mat-raised-button] {
      /* Material protected-button API: shape, elevation and the hover lift are
         the button's own tokens — the hand-drawn :hover rule is gone. */
      --mdc-protected-button-container-shape: 10px;
      --mdc-protected-button-container-elevation-shadow: var(--mat-app-elevation-shadow-level-1);
      --mdc-protected-button-hover-container-elevation-shadow: var(--mat-app-elevation-shadow-level-4);
      --mdc-protected-button-pressed-container-elevation-shadow: var(--mat-app-elevation-shadow-level-0);
      font-weight: 600;
      text-transform: none;
      letter-spacing: 0.5px;
      padding: 0 24px;
      height: 48px;
      width: 100%;
    }

    .btn-spinner {
      display: inline-block;
      vertical-align: middle;
      margin-right: 8px;
    }

    .mode-toggle {
      margin: 20px 0 0;
      font-size: 0.9rem;
      color: var(--rp-on-surface-variant) !important;
      text-align: center;
    }

    .link-btn {
      background: none;
      border: none;
      padding: 0;
      margin-left: 4px;
      color: var(--rp-primary) !important;
      font-size: inherit;
      font-weight: 600;
      cursor: pointer;
      text-decoration: underline;
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
