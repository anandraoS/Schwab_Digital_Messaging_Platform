import type { ReactNode } from 'react';

export function EmptyState({ title = 'Nothing to show', message = '', children }: { title?: string; message?: string; children?: ReactNode }) {
  return (
    <div className="empty" role="status">
      <strong>{title}</strong>
      {message ? <p className="muted">{message}</p> : null}
      {children}
    </div>
  );
}
