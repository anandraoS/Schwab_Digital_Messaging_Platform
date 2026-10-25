import { signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { NormalizedError, toNormalizedError } from '../http/error-mapper';

/**
 * Small signal-based holder for "load something over HTTP" state: data / loading / error.
 * Starting a new load cancels the previous in-flight one.
 */
export class Loadable<T> {
  readonly data = signal<T | null>(null);
  readonly loading = signal(false);
  readonly error = signal<NormalizedError | null>(null);
  private sub?: Subscription;

  load(source: Observable<T>, onSuccess?: (value: T) => void): void {
    this.sub?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.sub = source.subscribe({
      next: (value) => {
        this.data.set(value);
        this.loading.set(false);
        onSuccess?.(value);
      },
      error: (e: unknown) => {
        this.error.set(toNormalizedError(e));
        this.loading.set(false);
      },
    });
  }

  cancel(): void {
    this.sub?.unsubscribe();
  }
}
