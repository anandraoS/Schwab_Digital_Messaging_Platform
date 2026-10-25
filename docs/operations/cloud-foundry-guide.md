# Cloud Foundry Guide

**Execution status: NOT executed.** No Cloud Foundry foundation or cf CLI was available. What was run: the dependency-free
manifest validator (`node cloud-foundry/validate-manifest.mjs`) and `bash -n` on the CF scripts. Everything about real
`cf push`, bindings, routes and rolling deploys is documented guidance and unverified here.

## Files
| Path | Purpose |
|---|---|
| `cloud-foundry/manifest.yml` | backend app (java_buildpack, Java 21, rolling-compatible) |
| `cloud-foundry/manifest-frontend.yml` | Angular static assets via `staticfile_buildpack` |
| `cloud-foundry/vars/{dev,test}.yml` | per-space variables (`env`, `instances`, `memory`, `apps_domain`) |
| `cloud-foundry/scripts/{deploy,smoke,blue-green,rollback}.sh` | operational scripts (`bash -n` checked only) |
| `cloud-foundry/validate-manifest.mjs` | offline manifest linter (parses YAML subset; checks keys, memory format, health check, Java 21 pin, service bindings, inline-secret heuristics) |

## Operating model
- **Org / space.** An org groups teams/quota; a space is an environment (`dev`, `test`) with its own apps, routes, services and roles (SpaceDeveloper, SpaceManager, Auditor). Use one space per environment and keep manifests environment-neutral using `--vars-file`.
- **Push pipeline.** `cf push` uploads source/artifact, a **buildpack** detects/compiles it into a **droplet** (the runnable artifact), and the droplet runs in a **container** (Diego cell). Apps are stateless and disposable; the filesystem is ephemeral.
- **Buildpacks.** `java_buildpack` runs a jar with a JRE it provides. Java 21 is selected by `JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'`. The buildpack computes JVM memory settings from the container limit (memory calculator), so set `memory` deliberately (1G dev, 1.5G test). Confirm your foundation's java-buildpack version offers JRE 21 (`cf buildpacks`).
- **Health checks.** `health-check-type: http` against `/actuator/health/liveness`; `timeout: 180` is the startup budget (Flyway + JPA), `health-check-invocation-timeout: 5`. A failing check restarts the instance.
- **Routes.** `messaging-backend-<env>.<apps_domain>`; the gorouter load-balances instances and removes unhealthy ones. TLS terminates at the router; the manifest's frontend sets `FORCE_HTTPS`.
- **Service bindings and `VCAP_SERVICES`.** `messaging-postgres`, `messaging-rabbit`, `messaging-redis`, `messaging-mongo` are marketplace or user-provided service instances created beforehand (`cf create-service ...` / `cf cups ...`). Binding injects credentials as JSON in the `VCAP_SERVICES` env var (`cf env APP` shows it; treat it as sensitive).
  - Use **java-cfenv** (`io.pivotal.cfenv:java-cfenv-boot`) to map `VCAP_SERVICES` to Spring properties (datasource, rabbit, redis, mongo). Spring Cloud Connectors is end-of-life; do not use it. The manifest disables the buildpack's Spring auto-reconfiguration (`JBP_CONFIG_SPRING_AUTO_RECONFIGURATION`) because java-cfenv is the supported path.
  - **Repo gap:** the backend POM must include java-cfenv for the `cloud` profile to pick up bindings automatically. If it does not, map `VCAP_SERVICES` to the `SPRING_*` variables with `cf set-env` or a small startup shim. Not verified.
- **Secrets.** Never put secrets in the manifest (the validator flags `*password*`, `*secret*`, `*token*` env keys with literal values). Use `cf cups messaging-secrets -p '{"callbackSecret":"CHANGE_ME_LOCAL_ONLY"}'` (dummy example) and bind it, or CredHub-backed services.

## Deploying
```bash
bash scripts/mvn.sh -f backend/pom.xml -DskipTests package
cf login -a <api> -o <org> -s dev          # interactive; do not script passwords on the command line
bash cloud-foundry/scripts/deploy.sh dev    # validates manifests, rolling push, smoke
# equivalent manual command:
cf push -f cloud-foundry/manifest.yml --vars-file cloud-foundry/vars/dev.yml --strategy rolling
```
Prerequisites in the target space: the four service instances, a route/domain, quota for `instances x memory`.

### Rolling deploy
`--strategy rolling` creates a new process revision and replaces instances gradually, honoring health checks, so there is no downtime if at least one instance stays healthy. Constraint: schema migrations must be backward compatible (old and new versions overlap). Requires CF API v3 / CLI v8+.

### Blue-green (manual)
`cloud-foundry/scripts/blue-green.sh <env>` pushes `-green` without the production route, smoke tests it on a temporary route, maps the production route to it, unmaps blue, and keeps blue for rollback. Use it when you need full pre-switch verification or incompatible changes (with care for the shared database). Alternative: `cf push --strategy canary` on foundations that support it (check your CF version; unverified here).

### Scaling
```bash
cf scale messaging-backend-test -i 3          # horizontal
cf scale messaging-backend-test -m 2G -k 2G   # vertical (restarts)
cf app messaging-backend-test                 # instance state, CPU, memory
```
Consumers are in-process; more instances means more concurrent consumers against the shared RabbitMQ queue (check `messaging.dispatch.concurrency` and DB pool size per instance).

### Rollback
1. Revisions (preferred): `cf revisions APP` then `bash cloud-foundry/scripts/rollback.sh APP <revision>` (wraps `cf rollback APP --version N --strategy rolling`).
2. Droplets (older CLI/API): `cf droplets APP`, `cf set-droplet APP <droplet-guid>`, `cf restart APP --strategy rolling`.
3. Blue-green: re-map the route to the retained blue app.
Config/env changes are not part of a droplet: a revision rollback restores the previous droplet and environment snapshot as the platform defines it; verify env after rollback.

### Logs, events, debugging
```bash
cf logs messaging-backend-dev --recent        # recent buffer
cf logs messaging-backend-dev                 # live tail (Loggregator)
cf events messaging-backend-dev               # push/crash/scale history
cf ssh messaging-backend-dev                  # if SSH is enabled in the space
cf env messaging-backend-dev                  # includes VCAP_SERVICES (sensitive)
```
Logs are JSON on stdout; forward them via the platform's syslog drain if needed.

## Troubleshooting
| Symptom | Likely cause | Check / fix |
|---|---|---|
| `Staging error: no compatible JRE` | buildpack lacks Java 21 | `cf buildpacks`; pin a newer buildpack with `-b`, or ask the platform team |
| Instances crash after start (`CRASHED`, `OOMKilled`) | memory too low for heap + metaspace + threads | raise `memory`; check memory calculator output in staging logs |
| `Instance never became healthy` | liveness failing, slow start, DB unreachable | `cf logs --recent`; raise `timeout`; verify bindings (`cf services`, `cf env`) |
| 502/404 from router | route not mapped, app unhealthy, wrong port | `cf routes`, `cf app` |
| Cannot connect to datasource | missing java-cfenv mapping or binding not restaged | `cf restage APP` after binding changes; confirm `cloud` profile |
| Rolling push stuck | new revision failing health check | `cf logs`; `cf rollback` or `cf cancel-deployment APP` |
| Flyway failure | migrator lacks privileges | see `infrastructure/postgres/init.sql` role model |

## Cloud Foundry vs Kubernetes
| Topic | Cloud Foundry | Kubernetes |
|---|---|---|
| Unit of deploy | app (source/jar) staged to a droplet | container image |
| Developer interface | `cf push` + manifest | manifests/Helm/Kustomize + CI |
| Config | env vars, bound services (`VCAP_SERVICES`) | ConfigMap/Secret, env, volumes |
| Networking | gorouter routes, container networking policies | Service, Ingress, NetworkPolicy |
| Scaling | `cf scale` (manual; autoscaler service optional) | HPA/KEDA, cluster autoscaler |
| Rolling/blue-green | `--strategy rolling`, route mapping | Deployment strategies, Argo Rollouts/Flagger |
| Customization | opinionated, fewer knobs (buildpacks) | very flexible, more operational surface |
| Stateful dependencies | marketplace services | operators/StatefulSets/managed services |
| Best fit | standardized 12-factor apps, platform-team-run | heterogeneous workloads, custom infra needs |
This project ships both: `cloud-foundry/` for the CF path and `infrastructure/kubernetes|helm` for Kubernetes. They are not tested against live platforms here.

## Local practice path (guidance only; availability changes, verify before relying)
- **Korifi** (CNCF/Cloud Foundry community) provides the CF API on top of Kubernetes and can run on kind/minikube. It is the actively developed successor in the CF-on-Kubernetes space. Needs a local Kubernetes cluster (Docker required) and enough RAM; install steps change between releases, follow its current README.
- **cf-for-k8s** was the earlier approach and has been archived/deprecated; do not start new work on it.
- **Pivotal Web Services** was shut down; do not plan on it. Public free CF trials (for example vendor trial accounts such as SAP BTP trial) come and go and have usage limits; check terms and current availability.
- **Without any foundation:** use `node cloud-foundry/validate-manifest.mjs` and practice reading manifests, `VCAP_SERVICES` JSON handling and the commands above in the CF CLI documentation.
