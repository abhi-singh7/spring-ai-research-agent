import { Component } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { ViewChild, AfterViewInit } from '@angular/core';
import { inject } from '@angular/core';
import { AuthService } from './core/services/auth.service';
import { ResearchService } from './core/services/research.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule, MatButtonModule],
  template: `
    <mat-toolbar class="nav-toolbar">
      <span class="app-title">Research Agent</span>
      <span class="spacer"></span>
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
