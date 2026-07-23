import { Component } from '@angular/core';
import { Router,RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ViewChild, AfterViewInit } from '@angular/core';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule],
  template: `
    <mat-toolbar class="nav-toolbar">
      <span class="app-title">Research Agent</span>
      <span class="spacer"></span>
      <a routerLink="/research/new" routerLinkActive="active-link" mat-button>New Research</a>
      <a routerLink="/research/history" routerLinkActive="active-link" mat-button>History</a>
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
  `]
})


export class AppComponent implements AfterViewInit {

  @ViewChild(RouterOutlet)
  outlet!: RouterOutlet;

  ngAfterViewInit() {
    this.outlet.activateEvents.subscribe(component => {
     // console.log('ACTIVATED COMPONENT:', component);
      //console.log('COMPONENT TYPE:', component?.constructor?.name);
    });
  }
}
