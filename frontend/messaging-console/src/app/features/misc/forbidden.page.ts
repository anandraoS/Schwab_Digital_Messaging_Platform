import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../shared/empty-state';

@Component({
  selector: 'app-forbidden-page',
  imports: [EmptyStateComponent, RouterLink],
  template: `
    <div class="page">
      <app-empty-state title="Not permitted" message="Your role does not include this page. (UI hint only; the API enforces access.)">
        <a routerLink="/dashboard">Back to dashboard</a>
      </app-empty-state>
    </div>
  `,
})
export class ForbiddenPage {}
