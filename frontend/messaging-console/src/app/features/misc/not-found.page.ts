import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../shared/empty-state';

@Component({
  selector: 'app-not-found-page',
  imports: [EmptyStateComponent, RouterLink],
  template: `
    <div class="page">
      <app-empty-state title="Page not found" message="That address does not match any console page.">
        <a routerLink="/dashboard">Back to dashboard</a>
      </app-empty-state>
    </div>
  `,
})
export class NotFoundPage {}
