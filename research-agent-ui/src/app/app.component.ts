import { Component } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { ViewChild, AfterViewInit } from '@angular/core';
import { inject } from '@angular/core';
import { AuthService } from './core/services/auth.service';
import { ResearchService } from './core/services/research.service';
import { ThemeService } from './core/services/theme.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule, MatButtonModule],
  template: `
    <mat-toolbar class="nav-toolbar">
      <span class="app-title">Research Agent</span>
      <span class="spacer"></span>
      <!-- Scheme control: one icon-only option per scheme inside a track. The picked
           option carries class="theme-selected", which paints Material's segmented-button
           affordance (a filled pill in the brand colour with its on-brand glyph) from
           --rp-* tokens; ThemeService owns which option is picked (and persists it), and
           writes the scheme class on <html> — :root.rp-theme-dark in styles.scss.
           The glyphs are inline SVG (currentColor), not <mat-icon>: they cost no bundle
           bytes, need no icon webfont, and still read at any size. -->
      <span class="theme-switch">
        <button mat-button class="theme-opt" [class.theme-selected]="theme.choice() === 'auto'"
                aria-label="Colour scheme: follow the system setting"
                title="Follow the system setting" (click)="theme.setPreference('auto')">
          <svg class="theme-icon" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="7.25" fill="none" stroke="currentColor" stroke-width="1.7"/>
            <path d="M12 4.75a7.25 7.25 0 0 1 0 14.5z" fill="currentColor"/>
          </svg>
        </button>
        <button mat-button class="theme-opt" [class.theme-selected]="theme.choice() === 'light'"
                aria-label="Colour scheme: light" title="Light scheme"
                (click)="theme.setPreference('light')">
          <svg class="theme-icon" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="4.1" fill="currentColor"/>
            <path d="M12 2.9v2.6M12 18.5v2.6M2.9 12h2.6M18.5 12h2.6M5.6 5.6l1.9 1.9M16.5 16.5l1.9 1.9M5.6 18.4l1.9-1.9M16.5 7.5l1.9-1.9"
                  fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/>
          </svg>
        </button>
        <button mat-button class="theme-opt" [class.theme-selected]="theme.choice() === 'dark'"
                aria-label="Colour scheme: dark" title="Dark scheme"
                (click)="theme.setPreference('dark')">
          <svg class="theme-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M17.6 12.4a6.6 6.6 0 1 1-7 7a6.6 6.6 0 0 1 7-7z" fill="currentColor"/>
          </svg>
        </button>
      </span>
      <a routerLink="/research/new" routerLinkActive="active-link" mat-button>New Research</a>
      <a routerLink="/research/history" routerLinkActive="active-link" mat-button>History</a>
      @if (auth.authStatus() === 'authenticated') {
        <span class="user-chip">{{ auth.currentUser()?.username }}</span>
        <button mat-button class="logout-btn" (click)="logout()">Logout</button>
      }
    </mat-toolbar>

   <router-outlet></router-outlet>
  `,
  styles: [`
    .app-title { font-weight: bold; }
    .spacer { flex: 1; }

    /* The nav links are Material text buttons (a mat-button), so their chrome
       comes from the button's own tokens — and hover is Material's persistent
       state layer (brand tinted), not a hand-drawn background-color. */
    mat-toolbar a[mat-button],
    mat-toolbar a[routerLinkActive] {
      --mdc-text-button-label-text-color: var(--rp-on-surface);
      --mdc-text-button-container-shape: 8px;
      --mat-text-button-state-layer-color: var(--rp-primary);
      --mat-text-button-hover-state-layer-opacity: 0.12;
      text-decoration: none;
      padding: 0 16px;
      font-weight: 500;
    }

    /* Active route: the same token, one step louder. */
    mat-toolbar a.active-link {
      --mdc-text-button-label-text-color: var(--rp-primary);
      font-weight: 700;
    }

    mat-toolbar a[mat-button] + a[mat-button] { margin-left: 8px; }

    /* Username pill in the toolbar — a plain span, so it is painted from the
       same container/on-container token pair the status chips use. */
    .user-chip {
      margin-left: 12px;
      padding: 4px 12px;
      border-radius: 999px;
      background-color: var(--rp-primary-container);
      color: var(--rp-on-primary-container);
      font-weight: 600;
      font-size: 0.85rem;
    }

    /* The scheme control: an icon-only segmented switch — a track holding three square
       icon buttons, where the picked option wears a filled primary-container pill
       (Material's segmented-button affordance, painted from --rp-* tokens). Icons carry
       the meaning, so nothing here is labelled by text; the buttons keep aria-labels and
       the picked one is additionally distinguished by shape (pill), not colour alone. */
    .theme-switch {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 6px;
      margin-right: 12px;
      border-radius: 999px;
      /* Hairline track + a faint brand wash, so the three glyphs read as one control
         against the toolbar. Both come from theme tokens (no hand-picked colours). */
      border: 1px solid color-mix(in oklab, var(--rp-outline) 55%, transparent);
      background-color: var(--rp-brand-08);
    }
    mat-toolbar .theme-opt {
      /* Icon-only text button: square hit area, no label, glyph centred. Material's text
         button defaults a 64px min-inline-size (for its label); zeroing that token is
         what makes an icon-only button square. The state layer stays brand-tinted. */
      display: inline-flex;
      align-items: center;
      justify-content: center;
      --mdc-text-button-min-inline-size: 0px;
      min-inline-size: 0;
      width: 30px;
      height: 30px;
      padding: 0;
      border-radius: 999px;
      color: var(--rp-on-surface);
      --mdc-text-button-label-text-color: var(--rp-on-surface);
      --mat-text-button-state-layer-color: var(--rp-primary);
      --mat-text-button-hover-state-layer-opacity: 0.10;
      --mdc-text-button-container-shape: 999px;
    }
    mat-toolbar .theme-opt mat-icon,
    mat-toolbar .theme-opt svg.theme-icon {
      width: 20px;
      height: 20px;
      font-size: 20px;
    }
    /* Picked option: a filled pill in the brand colour with its on-brand glyph —
       Material's "selected segment" affordance. primary/on-primary is used rather than
       the container pair because M3's primary-container is too close to the toolbar
       surface in the dark scheme. The glyph inherits, it is painted with currentColor. */
    mat-toolbar .theme-selected {
      --mdc-text-button-label-text-color: var(--rp-on-primary);
      color: var(--rp-on-primary);
      background-color: var(--rp-primary);
    }

    /* Material text button: hover is its state layer, tinted to the brand. */
    .logout-btn {
      margin-left: 8px;
      --mdc-text-button-container-shape: 8px;
      --mat-text-button-state-layer-color: var(--rp-primary);
      --mat-text-button-hover-state-layer-opacity: 0.12;
      font-weight: 500;
    }
  `]
})


export class AppComponent implements AfterViewInit {

  @ViewChild(RouterOutlet)
  outlet!: RouterOutlet;

  private router = inject(Router);
  readonly auth = inject(AuthService);
  readonly theme = inject(ThemeService);
  private researchService = inject(ResearchService);

  constructor() {
    // Kick off the boot-time session check before initial navigation renders any route,
    // so the guard awaits a request that is already in flight.
    this.auth.restoreSession();
  }

  ngAfterViewInit() {
    this.outlet.activateEvents.subscribe(component => {
     // console.log('ACTIVATED COMPONENT:', component);
      //console.log('COMPONENT TYPE:', component?.constructor?.name);
    });
  }

  logout(): void {
    this.researchService.clearActiveWork();
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
