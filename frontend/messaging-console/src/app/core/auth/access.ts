import { Role } from '../models/api.models';

/**
 * Role matrix mirroring CONTRACT section 5. UX only: the backend is the source of truth and
 * will answer 403/404 regardless of what the UI shows.
 */
export const ACCESS = {
  submit: ['SENDER', 'TENANT_ADMIN', 'PLATFORM_ADMIN'],
  cancel: ['SENDER', 'TENANT_ADMIN', 'OPERATOR', 'PLATFORM_ADMIN'],
  requeue: ['OPERATOR', 'PLATFORM_ADMIN'],
  deadLetterAct: ['OPERATOR', 'PLATFORM_ADMIN'],
  deadLetterView: ['OPERATOR', 'TENANT_ADMIN', 'PLATFORM_ADMIN'],
  failedView: ['OPERATOR', 'TENANT_ADMIN', 'PLATFORM_ADMIN'],
  deliveryEvents: ['OPERATOR', 'AUDITOR', 'TENANT_ADMIN', 'PLATFORM_ADMIN'],
  audit: ['AUDITOR', 'TENANT_ADMIN', 'PLATFORM_ADMIN'],
  templateAuthor: ['TEMPLATE_AUTHOR', 'TENANT_ADMIN'],
  templateEdit: ['TEMPLATE_AUTHOR'],
  templateApprove: ['TEMPLATE_APPROVER'],
  templateRetire: ['TEMPLATE_APPROVER', 'TENANT_ADMIN'],
  templatePreview: ['TEMPLATE_AUTHOR', 'TENANT_ADMIN', 'OPERATOR'],
  preferences: ['OPERATOR', 'TENANT_ADMIN', 'PLATFORM_ADMIN', 'SENDER'],
  preferencesEdit: ['TENANT_ADMIN', 'OPERATOR', 'PLATFORM_ADMIN'],
  bulk: ['SENDER', 'TENANT_ADMIN', 'PLATFORM_ADMIN'],
  providers: ['OPERATOR', 'TENANT_ADMIN', 'PLATFORM_ADMIN'],
  platformAdmin: ['PLATFORM_ADMIN'],
  queues: ['OPERATOR', 'PLATFORM_ADMIN'],
} as const satisfies Record<string, readonly Role[]>;

export interface NavItem {
  label: string;
  path: string;
  roles?: readonly Role[];
}

export const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', path: '/dashboard' },
  { label: 'Messages', path: '/messages' },
  { label: 'New message', path: '/messages/new', roles: ACCESS.submit },
  { label: 'Templates', path: '/templates' },
  { label: 'Preferences', path: '/preferences', roles: ACCESS.preferences },
  { label: 'Bulk jobs', path: '/bulk-jobs', roles: ACCESS.bulk },
  { label: 'Providers', path: '/providers', roles: ACCESS.providers },
  { label: 'Queues', path: '/queues', roles: ACCESS.queues },
  { label: 'Failed messages', path: '/failed', roles: ACCESS.failedView },
  { label: 'Dead letters', path: '/dead-letters', roles: ACCESS.deadLetterView },
  { label: 'Delivery events', path: '/delivery-events', roles: ACCESS.deliveryEvents },
  { label: 'Audit history', path: '/audit', roles: ACCESS.audit },
  { label: 'Admin', path: '/admin', roles: ACCESS.platformAdmin },
];
