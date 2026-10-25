# Template Approval Flow

```mermaid
stateDiagram-v2
  [*] --> DRAFT: POST /templates or /versions (TEMPLATE_AUTHOR)
  DRAFT --> DRAFT: PUT version (optimistic lock)
  DRAFT --> PENDING_APPROVAL: submit (author)
  PENDING_APPROVAL --> PUBLISHED: approve (APPROVER != author)
  PENDING_APPROVAL --> DRAFT: reject with comment
  PUBLISHED --> RETIRED: retire, or superseded by newer approved version of same locale
  RETIRED --> [*]
```

```mermaid
sequenceDiagram
  autonumber
  participant A as Author
  participant API as TemplateController
  participant TS as TemplateService
  participant AU as AuthorizationService
  participant DB as Database
  participant AP as Approver
  A->>API: POST /templates (code, channel, category, first DRAFT)
  API->>TS: create
  TS->>DB: template + template_version(DRAFT) + audit
  A->>API: PUT /templates/{id}/versions/1?locale=en (If-Match version)
  API->>TS: edit DRAFT only (409 CONFLICT_OPTIMISTIC_LOCK if stale)
  A->>API: POST .../submit
  TS->>DB: DRAFT -> PENDING_APPROVAL, audit
  AP->>API: POST .../approve
  API->>AU: approver != created_by, role TEMPLATE_APPROVER, tenant owns template
  TS->>DB: PENDING_APPROVAL -> PUBLISHED (approved_by, published_at)
  TS->>DB: previous PUBLISHED same locale -> RETIRED
  TS->>DB: audit TEMPLATE_PUBLISHED
```

## Controls
- Preview (`POST /templates/preview`) renders with sample data using the same `TemplateRenderer`, never sends.
- `TemplateRenderer` is an allow-listed `{{var}}` substitution: variables declared in `variables_json`; unknown variable = `TEMPLATE_RENDER_ERROR`; HTML escaping for email HTML; no expression evaluation, no includes, no reflection.
- Category binding: template category is fixed per template so marketing content cannot be sent as TRANSACTIONAL (ADR-0012).
- Published versions are immutable; edits require a new version (ADR-0007).
- Four-eyes: approver and author must differ; enforced server-side.
- Audit trail: `GET /api/v1/templates/{id}/history`.
