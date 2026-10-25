import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import {
  authInterceptor, correlationInterceptor, errorInterceptor, loadingInterceptor,
} from './core/http/interceptors';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    // Order matters: correlation id first, then auth, then loading indicator, error normalization last.
    provideHttpClient(withInterceptors([correlationInterceptor, authInterceptor, loadingInterceptor, errorInterceptor])),
  ],
};
