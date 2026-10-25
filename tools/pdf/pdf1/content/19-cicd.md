# 19. CI/CD Pipeline

> [!WARNING] Execution status: NOT executed
> None of the three pipeline definitions has run on a CI server. Their YAML was parsed locally by `bash scripts/validate-infra.sh` (using the in-repo parser `scripts/lib/yaml-lite.mjs`); the Jenkinsfile Groovy was not linted. Steps that call Docker, Trivy, `cf`, kubeconform, helm or shellcheck were never run on the authoring machine.

## 19.1 One pipeline, three syntaxes

The same stage map is expressed for GitHub Actions (`.github/workflows/ci.yml`, `release.yml`), Jenkins (`Jenkinsfile`) and Bamboo (`bamboo-specs/bamboo.yaml`), because enterprises differ in what they run, and because a pipeline described three times shows that the stages are the design, not the tool.

{{diagram:ci}}

## 19.2 Stages and gates

| Stage | Purpose | Merge-blocking |
|---|---|---|
| Version | Compute `<base>.<run number>` (base `0.1`) | n/a |
| Backend build and test | `mvn verify` (unit and integration tests on H2, in-memory adapters) | Yes |
| Frontend | Lint, Karma unit tests, production build | Yes |
| Static analysis | Profile `-Pstatic-analysis` | Yes |
| OWASP dependency-check | Profile `-Psecurity-scan`, optional `NVD_API_KEY` | Yes |
| npm audit | Frontend dependency vulnerabilities | Yes |
| Integration tests | Profile `-Pcontainers`: Testcontainers for PostgreSQL, RabbitMQ, Redis, MongoDB | Yes in CI |
| Config validation | Compose, kustomize, kubeconform, helm, CF manifest, shellcheck, promtool, repository checks | Yes |
| Docker build, Trivy, SBOM | Image build; Trivy HIGH or CRITICAL (unfixed ignored); CycloneDX SBOM | Yes |
| Deploy to test | Manual, only from `main`, secrets-gated | n/a |
| Post-deploy smoke | `cloud-foundry/scripts/smoke.sh`: liveness, readiness, unauthenticated `/api/v1/me` is 401 | Gate for the deploy |
| Release | Tag `v*.*.*` publishes images with that exact semantic version | n/a |

A skipped deploy (no secrets configured) reports a clear "skipped" summary and is **not** a pass.

## 19.3 Artifact versioning and immutability

- Build version: `<base>.<run number>`, for example `0.1.57`.
- Image tag: `<version>-<git sha7>`, for example `0.1.57-3fa9c21`; Docker tags cannot contain `+`.
- Images carry OCI labels `version` and `revision`. Deploy by immutable tag or digest, never `latest`.
- Bump `VERSION_BASE` deliberately in the workflow, the Jenkinsfile and the Bamboo variable together.

## 19.4 Secrets in the pipeline

| Name | Used for | Where |
|---|---|---|
| `NVD_API_KEY` | Faster OWASP dependency-check (optional) | GitHub secret, Jenkins credential `nvd-api-key`, Bamboo variable |
| `CF_API`, `CF_USERNAME`, `CF_PASSWORD`, `CF_ORG`, `CF_SPACE` | Test deploy | GitHub environment `test`, Jenkins `cf-test-creds`, Bamboo variables |
| `GITHUB_TOKEN` | Image push in the release workflow | Provided by Actions |

No real secret appears in any pipeline file; Bamboo `CHANGE_ME_LOCAL_ONLY` entries are dummy placeholders.

## 19.5 What can be reproduced locally

```bash
bash scripts/validate-infra.sh          # static validations; prints SKIPPED for absent tools
node cloud-foundry/validate-manifest.mjs
```

The backend and frontend test suites can be run locally (`bash scripts/mvn.sh clean verify`, `npm test` in `frontend/messaging-console`); the results that were actually produced are recorded in `docs/validation/VALIDATION_REPORT.md`.

## 19.6 Known gaps recorded by the pipeline author

- Profiles `static-analysis`, `security-scan` and `containers` must exist in `backend/pom.xml`; the static-analysis job fails fast if its profile id is missing. No Checkstyle or SpotBugs configuration files were added under `backend/config/`.
- `cloud-foundry/manifest.yml` expects `backend/target/messaging-platform.jar`; align the jar name with the POM `finalName`.
- Third-party actions are tag-pinned, not SHA-pinned; pin to SHAs in a real organisation.
- The Jenkins agent needs labels `linux && docker`, tools `jdk21` and `nodejs22`, plus trivy and cf CLIs.

## 19.7 Release readiness and rollback

The release gate is a checklist, not a feeling: `docs/operations/release-readiness-checklist.md` (tests, security, migrations, observability, runbooks) and `docs/operations/rollback-plan.md` (every release is rollback-safe by construction: expand-only schema changes and backward-compatible messages and API, so rollback means deploying the previous immutable version rather than restoring data).

## Reference material

{{embed:docs/operations/ci-cd.md|title=CI/CD documentation}}

{{embed:docs/operations/release-readiness-checklist.md|title=Release readiness checklist}}

{{embed:docs/operations/rollback-plan.md|title=Rollback plan}}

{{embed:docs/leadership/pr-checklist.md|title=Pull request checklist}}
