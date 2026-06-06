import { Component } from '@angular/core';
import { Router,RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ViewChild, AfterViewInit } from '@angular/core';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule],
  template: `
    <mat-toolbar color="primary">
      <span class="app-title">Research Agent</span>
      <span class="spacer"></span>
      <a mat-button routerLink="/research/new" routerLinkActive="active-link">New Research</a>
      <a mat-button routerLink="/research/history" routerLinkActive="active-link">History</a>
    </mat-toolbar>

   <router-outlet></router-outlet>
  `,
  styles: [`
    .app-title { font-weight: bold; }
    .spacer { flex: 1; }
    .active-link { font-weight: bold; text-decoration: underline; }
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
