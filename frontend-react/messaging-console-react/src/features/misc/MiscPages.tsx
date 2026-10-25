import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';

export function ForbiddenPage() {
  return (
    <div className="page">
      <EmptyState title="Not permitted" message="Your role does not include this page. (UI hint only; the API enforces access.)">
        <Link to="/dashboard">Back to dashboard</Link>
      </EmptyState>
    </div>
  );
}

export function NotFoundPage() {
  return (
    <div className="page">
      <EmptyState title="Page not found" message="There is nothing at this address.">
        <Link to="/dashboard">Back to dashboard</Link>
      </EmptyState>
    </div>
  );
}
