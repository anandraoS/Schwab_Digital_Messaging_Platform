import { lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { ACCESS } from '../core/auth/access';
import { RequireAuth, RequireRole, Titled } from './guards';
import { Shell } from './Shell';

// All feature pages are lazy-loaded chunks (React.lazy). Role guards are UX only; the API enforces authorization.
const lazyPage = <K extends string>(loader: () => Promise<Record<K, React.ComponentType>>, name: K) =>
  lazy(async () => ({ default: (await loader())[name] }));

const LoginPage = lazyPage(() => import('../features/login/LoginPage'), 'LoginPage');
const DashboardPage = lazyPage(() => import('../features/dashboard/DashboardPage'), 'DashboardPage');
const MessageListPage = lazyPage(() => import('../features/messages/MessageListPage'), 'MessageListPage');
const MessageSubmitPage = lazyPage(() => import('../features/messages/MessageSubmitPage'), 'MessageSubmitPage');
const MessageDetailPage = lazyPage(() => import('../features/messages/MessageDetailPage'), 'MessageDetailPage');
const TemplateListPage = lazyPage(() => import('../features/templates/TemplateListPage'), 'TemplateListPage');
const TemplateDetailPage = lazyPage(() => import('../features/templates/TemplateDetailPage'), 'TemplateDetailPage');
const PreferencesPage = lazyPage(() => import('../features/preferences/PreferencesPage'), 'PreferencesPage');
const BulkJobListPage = lazyPage(() => import('../features/bulk-jobs/BulkJobListPage'), 'BulkJobListPage');
const BulkJobDetailPage = lazyPage(() => import('../features/bulk-jobs/BulkJobDetailPage'), 'BulkJobDetailPage');
const ProvidersPage = lazyPage(() => import('../features/providers/ProvidersPage'), 'ProvidersPage');
const QueuesPage = lazyPage(() => import('../features/queues/QueuesPage'), 'QueuesPage');
const FailedPage = lazyPage(() => import('../features/failed/FailedPage'), 'FailedPage');
const DeadLettersPage = lazyPage(() => import('../features/dead-letters/DeadLettersPage'), 'DeadLettersPage');
const DeliveryEventsPage = lazyPage(() => import('../features/delivery-events/DeliveryEventsPage'), 'DeliveryEventsPage');
const AuditPage = lazyPage(() => import('../features/audit/AuditPage'), 'AuditPage');
const AdminPage = lazyPage(() => import('../features/admin/AdminPage'), 'AdminPage');
const ForbiddenPage = lazyPage(() => import('../features/misc/MiscPages'), 'ForbiddenPage');
const NotFoundPage = lazyPage(() => import('../features/misc/MiscPages'), 'NotFoundPage');

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Titled title="Sign in"><LoginPage /></Titled>} />
      <Route element={<RequireAuth />}>
        <Route element={<Shell />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="dashboard" element={<Titled title="Dashboard"><DashboardPage /></Titled>} />
          <Route path="messages" element={<Titled title="Messages"><MessageListPage /></Titled>} />
          <Route element={<RequireRole roles={ACCESS.submit} />}>
            <Route path="messages/new" element={<Titled title="New message"><MessageSubmitPage /></Titled>} />
          </Route>
          <Route path="messages/:id" element={<Titled title="Message detail"><MessageDetailPage /></Titled>} />
          <Route path="templates" element={<Titled title="Templates"><TemplateListPage /></Titled>} />
          <Route path="templates/:id" element={<Titled title="Template"><TemplateDetailPage /></Titled>} />
          <Route element={<RequireRole roles={ACCESS.preferences} />}>
            <Route path="preferences" element={<Titled title="Preferences"><PreferencesPage /></Titled>} />
          </Route>
          <Route element={<RequireRole roles={ACCESS.bulk} />}>
            <Route path="bulk-jobs" element={<Titled title="Bulk jobs"><BulkJobListPage /></Titled>} />
            <Route path="bulk-jobs/:id" element={<Titled title="Bulk job"><BulkJobDetailPage /></Titled>} />
          </Route>
          <Route element={<RequireRole roles={ACCESS.providers} />}>
            <Route path="providers" element={<Titled title="Providers"><ProvidersPage /></Titled>} />
          </Route>
          <Route element={<RequireRole roles={ACCESS.queues} />}>
            <Route path="queues" element={<Titled title="Queues"><QueuesPage /></Titled>} />
          </Route>
          <Route element={<RequireRole roles={ACCESS.failedView} />}>
            <Route path="failed" element={<Titled title="Failed messages"><FailedPage /></Titled>} />
          </Route>
          <Route element={<RequireRole roles={ACCESS.deadLetterView} />}>
            <Route path="dead-letters" element={<Titled title="Dead letters"><DeadLettersPage /></Titled>} />
          </Route>
          <Route element={<RequireRole roles={ACCESS.deliveryEvents} />}>
            <Route path="delivery-events" element={<Titled title="Delivery events"><DeliveryEventsPage /></Titled>} />
          </Route>
          <Route element={<RequireRole roles={ACCESS.audit} />}>
            <Route path="audit" element={<Titled title="Audit history"><AuditPage /></Titled>} />
          </Route>
          <Route element={<RequireRole roles={ACCESS.platformAdmin} />}>
            <Route path="admin" element={<Titled title="Admin"><AdminPage /></Titled>} />
          </Route>
          <Route path="forbidden" element={<Titled title="Not permitted"><ForbiddenPage /></Titled>} />
          <Route path="*" element={<Titled title="Not found"><NotFoundPage /></Titled>} />
        </Route>
      </Route>
    </Routes>
  );
}
