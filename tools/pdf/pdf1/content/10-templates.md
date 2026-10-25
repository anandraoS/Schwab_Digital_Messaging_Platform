# 10. Template Management

## 10.1 Why templates are governed

A template error sent at scale is a public incident: a wrong fee in a trade confirmation, a broken link in a password reset, a marketing sentence inside a security alert. The platform treats template content as **controlled change**: versioned, previewed, approved by a second person, and pinned to each message at accept time.

## 10.2 Model

| Concept | Table and key | Notes |
|---|---|---|
| Template | `template`, `UNIQUE(tenant_id, code, channel)` | Carries the **category**, fixed per template so that marketing content cannot be sent as TRANSACTIONAL (ADR-0012) |
| Template version | `template_version`, `UNIQUE(template_id, version_no, locale)` | Status, subject, body, declared `variables_json`, `created_by`, `approved_by`, `published_at`, optimistic `version` |
| Locale variants | Same `version_no`, different `locale` | `en`, `es`, ... Synthetic seed data ships templates in `en` and `es` |

{{diagram:templateStates}}

## 10.3 Approval flow

{{diagram:templateFlow}}

Controls enforced server side (`TemplateService`, `AuthorizationService`):

| Control | Effect |
|---|---|
| Four eyes | The approver must differ from `created_by`. An edit overwrites `created_by` with the editor (author of record), so a second person editing a draft cannot sidestep four-eyes approval (DEVIATIONS) |
| Immutability | Only `DRAFT` versions can be edited; a published version never changes, edits require a new version |
| Optimistic locking | `PUT` carries the version field; a stale write is 409 `CONFLICT_OPTIMISTIC_LOCK` |
| Supersede | Approving a version retires the previously published version of the same locale in the same step |
| Duplicates | A duplicate code and channel, or a duplicate locale version, is 409 `DUPLICATE_RESOURCE` |
| Roles | Authors create and edit; approvers approve, reject and retire; `TENANT_ADMIN` can also author and retire |
| Audit | Every step is audited; `GET /api/v1/templates/{id}/history` returns the trail |

## 10.4 The safe renderer

`TemplateRenderer` is deliberately not a general template engine. Its grammar is `{{name}}` or `{{name|default}}` and nothing else.

```java
// com.example.messaging.template.TemplateRenderer (class comment)
// Grammar: {{name}} or {{name|default}}. There is no expression evaluation of any kind: anything that is not a
// plain identifier inside the braces is a template error ({{ 7*7 }}, {{a.b}}, {{{x}}}).
// Only variables declared by the template are accepted. For EMAIL, values (and defaults) are HTML-escaped.
```

| Property | Rule |
|---|---|
| No code execution | No expressions, includes, reflection or property paths; `${...}` is literal text |
| Declared variables only | An undeclared placeholder is an authoring error; an unsupplied required variable is `TEMPLATE_VARIABLE_MISSING` |
| Escaping | HTML escaping of values for EMAIL; CR and LF stripped from subjects and titles |
| Limits | 50 variables, 2000 characters per value, 200 characters per default |
| Same code path for preview | `POST /api/v1/templates/preview` uses the same renderer and never sends |

Authoring example (from the OpenAPI spec): `<p>Use code {{code}} to reset your password. It expires in {{minutes|15}} minutes.</p>` with `variables: [code, minutes]`.

## 10.5 Pinning (ADR-0007)

At accept time the platform resolves the latest `PUBLISHED` version for the template code, locale and channel (or the explicit version requested) and stores `template_code`, `template_version_no` and `template_locale` on the message. Variables are stored; the **rendered body is not**. Rendering happens again at dispatch with the pinned version. Consequences:

- A template retired or superseded after accept still renders for messages already accepted.
- An accepted message cannot change meaning because someone published a new version.
- Message storage holds no rendered personal content at rest.

## 10.6 Tests that prove it

| Test | Proves |
|---|---|
| `TemplateRendererTest` | Grammar, escaping, error cases, limits |
| `TemplateLifecycleIT` | Draft, submit, approve, reject, retire; four-eyes; supersede; optimistic lock |
| `NotificationApiIT` | Pinning and missing-variable behaviour at submit |
| `SecurityMatrixTest` | Role matrix on template endpoints |

## Reference material

{{embed:docs/architecture/template-approval-flow.md|title=Template approval flow (design document)}}

{{embed:docs/adr/0007-template-versioning-and-pinning.md|title=ADR-0007: Template versioning and pinning}}
