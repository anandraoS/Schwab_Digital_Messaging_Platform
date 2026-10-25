# CI/CD

**Execution status: NOT executed.** None of the three pipeline definitions has run on a CI server. Their YAML was parsed
locally (`bash scripts/validate-infra.sh`, in-repo parser `scripts/lib/yaml-lite.mjs`); the Jenkinsfile Groovy was not
linted. Steps that call Docker, Trivy, cf, kubeconform, helm or shellcheck were never run on the authoring machine.

## Stage map
| Stage | GitHub Actions (`.github/workflows/ci.yml`) | Jenkins (`Jenkinsfile`) | Bamboo (`bamboo-specs/bamboo.yaml`) |
|---|---|---|---|
| Version | job `version` | stage `Version` | `${bamboo.version_base}.${bamboo.buildNumber}` in job script |
| Backend build + unit tests | `backend-build-test` (`mvn verify`) | `Build and test` / `Backend verify` | stage Build and Test / job Backend Verify |
| Frontend lint/test/build | `frontend` | `Frontend lint, test, build` | job Frontend Build |
| Static analysis | `static-analysis` (`-Pstatic-analysis`) | `Static analysis` | job Static Analysis |
| OWASP dependency-check | `owasp-dependency-check` (`-Psecurity-scan`, optional `NVD_API_KEY`) | `OWASP dependency-check` (`withCredentials` id `nvd-api-key`) | job Dependency Check |
| npm audit | `npm-audit` | `npm audit` | job Dependency Check |
| Integration tests | `backend-integration-tests` (`-Pcontainers`) | `Integration tests (Testcontainers)` | job Integration Tests |
| Config validation | `config-validation` (compose, kustomize, kubeconform, helm, CF manifest, shellcheck, promtool, repo checks) | `Config validation` | job Config Validation |
| Docker build + Trivy + SBOM | `docker-build-scan` (matrix backend/frontend) | `Docker build and scan` | stage Package / job Docker Images |
| Deploy to test (manual) | `deploy-test` (`workflow_dispatch`, environment `test`, secrets-gated) | `Deploy to test` (param + `input` gate, credentials id `cf-test-creds`) | stage Deploy Test (manual) |
| Post-deploy smoke | final step in `deploy-test` (`cloud-foundry/scripts/smoke.sh`) | `Post-deploy smoke` | task in Deploy Test CF |
| Release | `.github/workflows/release.yml` on tag `v*.*.*` | not defined | deployment project placeholder |

## Artifact versioning
- Build version: `<base>.<run number>` where `base` is `0.1` (`VERSION_BASE`), e.g. `0.1.57`.
- Image tag: `<version>-<git sha7>`, e.g. `0.1.57-3fa9c21`. Docker tags cannot contain `+`, so build metadata is joined with `-`.
- Release builds: tag `vMAJOR.MINOR.PATCH` publishes images tagged with exactly that semantic version (`release.yml`).
- Images carry OCI labels (`version`, `revision`) via build args `VERSION` and `GIT_SHA`. Deploy by immutable tag (ideally digest), never `latest`.
- Bump `VERSION_BASE` (major/minor) deliberately in the workflow, Jenkinsfile and Bamboo variable together.

## Secrets and credentials
| Name | Used for | Where defined |
|---|---|---|
| `NVD_API_KEY` | faster OWASP dependency-check (optional) | GitHub secret / Jenkins credential `nvd-api-key` / Bamboo variable `nvd_api_key_password` |
| `CF_API`, `CF_USERNAME`, `CF_PASSWORD`, `CF_ORG`, `CF_SPACE` | CF test deploy | GitHub environment `test` secrets / Jenkins `cf-test-creds` / Bamboo variables |
| `GITHUB_TOKEN` | GHCR push in release | provided by Actions |
No real secret appears in any pipeline file. Bamboo `CHANGE_ME_LOCAL_ONLY` entries are dummy placeholders.
The deploy job checks that secrets exist and reports a clear "skipped" summary when they do not: a skipped deploy is not a pass.

## Gates
Merge-blocking: backend verify, frontend, config validation, static analysis, Trivy HIGH/CRITICAL (unfixed ignored), OWASP, npm audit.
Deploy is manual and only from `main`. Smoke tests assert liveness, readiness and that unauthenticated `/api/v1/me` returns 401.

## Known gaps and assumptions
- Profiles `static-analysis`, `security-scan`, `containers` must exist in `backend/pom.xml`. The static-analysis job fails fast
  if its profile id is missing; the other two would only warn, so confirm them (lead action). No Checkstyle/SpotBugs config files were added under `backend/config/`.
- `deploy-test` pushes `backend/target/*.jar` and `cloud-foundry/manifest.yml` expects `backend/target/messaging-platform.jar`; align the jar name with `finalName` in the POM.
- Third-party actions are tag-pinned, not SHA-pinned. Pin to SHAs in a real organization.
- The Docker builds assume `frontend/messaging-console/package-lock.json` exists and the Angular output path is `dist/messaging-console/browser`.
- Jenkins agent needs labels `linux && docker`, tools `jdk21` and `nodejs22`, plus trivy and cf CLIs.
- SBOMs: GitHub uses anchore/sbom-action (CycloneDX JSON); Jenkins/Bamboo use `trivy image --format cyclonedx`.

## Reproduce locally (what can run without CI)
```bash
bash scripts/validate-infra.sh          # static validations; prints SKIPPED for absent tools
node cloud-foundry/validate-manifest.mjs
```
