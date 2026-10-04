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

    mat-toolbar a[mat-button],
    mat-toolbar a[routerLinkActive] {
      color: inherit !important;
      text-decoration: none !important;
      border-radius: 8px;
      padding: 0 16px;
      font-weight: 500;
      transition: all 0.2s ease;
    }

    mat-toolbar a[mat-button]:hover,
    mat-toolbar a[routerLinkActive]:hover {
      background-color: rgba(99, 102, 241, 0.1);
    }

    .active-link { font-weight: 700; color: #6366f1 !important; }

    mat-toolbar a[mat-button] + a[mat-button] { margin-left: 8px; }

    .user-chip {
      margin-left: 12px;
      padding: 4px 12px;
      border-radius: 999px;
      background-color: rgba(99, 102, 241, 0.12);
      color: #6366f1 !important;
      font-weight: 600;
      font-size: 0.85rem;
    }

    .logout-btn {
      margin-left: 8px;
      border-radius: 8px;
      font-weight: 500;
    }

    .logout-btn:hover {
      background-color: rgba(99, 102, 241, 0.1);
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
