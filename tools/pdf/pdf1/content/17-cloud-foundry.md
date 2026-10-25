# 17. Cloud Foundry Deployment

> [!WARNING] Execution status: NOT executed
> No Cloud Foundry foundation or `cf` CLI was available on the authoring machine. What did run: the dependency-free manifest validator (`node cloud-foundry/validate-manifest.mjs`) and `bash -n` on the CF scripts. Everything about real `cf push`, bindings, routes and rolling deploys is documented guidance and is unverified here.

## 17.1 Why Cloud Foundry is a first-class target

Many large enterprises run application platforms where developers push an artifact and the platform builds, runs and routes it. The platform is a **twelve-factor application**: configuration comes from the environment, backing services are attached resources, processes are stateless and disposable, logs go to stdout. That makes Cloud Foundry and Kubernetes equally viable, and ADR-0008 records the decision to ship manifests for both while treating neither as the one true runtime.

{{diagram:cf}}

## 17.2 The manifest

`cloud-foundry/manifest.yml` is parameterised with `((env))`, `((instances))`, `((memory))` and `((apps_domain))` taken from `cloud-foundry/vars/dev.yml` and `test.yml`.

```yaml
applications:
  - name: messaging-backend-((env))
    path: ../backend/target/messaging-platform.jar
    buildpacks: [java_buildpack]
    instances: ((instances))
    memory: ((memory))
    health-check-type: http
    health-check-http-endpoint: /actuator/health/liveness
    timeout: 180
    env:
      SPRING_PROFILES_ACTIVE: prod-like,rabbit,cloud
      JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
      MESSAGING_BUS_TYPE: rabbit
      MESSAGING_COORDINATION_TYPE: redis
      MESSAGING_SECURITY_DEV_TOKEN_ENABLED: 'false'
      SERVER_SHUTDOWN: graceful
    services: [messaging-postgres, messaging-rabbit, messaging-redis, messaging-mongo]
```

| Element | Why |
|---|---|
| `health-check-type: http` on `/actuator/health/liveness` | A failing liveness check restarts the instance; `timeout: 180` is the startup budget for Flyway and JPA |
| `JBP_CONFIG_SPRING_AUTO_RECONFIGURATION` disabled | Explicit configuration, no hidden auto-reconfiguration |
| `JAVA_OPTS: -Duser.timezone=UTC -XX:+ExitOnOutOfMemoryError` | UTC everywhere; an out-of-memory instance exits and is replaced rather than limping |
| `SERVER_SHUTDOWN: graceful` | In-flight deliveries drain within 30 s before the instance stops |
| Dev token disabled | The dev issuer can never be enabled by a forgotten setting in a deployed space |
| Services bound by name | The four backing services (PostgreSQL, RabbitMQ, Redis, MongoDB) are created beforehand |
| No secrets in the file | JWT issuer and callback HMAC secrets come from `cf set-env` or a user-provided service; the validator flags literal values on `*password*`, `*secret*`, `*token*` keys |

`cloud-foundry/manifest-frontend.yml` pushes the Angular static assets with the `staticfile_buildpack`.

## 17.3 Operating model

| Topic | Practice |
|---|---|
| Org and space | One space per environment (`dev`, `test`) |
| Push pipeline | `cf push` uploads the artifact; a buildpack produces a droplet; the droplet runs in a container |
| Service bindings | `VCAP_SERVICES` mapped to Spring properties by java-cfenv (**repo gap:** the backend POM must include it for the `cloud` profile to bind automatically; otherwise map to `SPRING_*` variables with `cf set-env`) |
| Rolling deploy | `cf push ... --strategy rolling`: schema migrations must be backward compatible with the previous release while both run |
| Blue-green | `cloud-foundry/scripts/blue-green.sh`: push `-green`, smoke it on a temporary route, switch the production route, keep blue for rollback |
| Scaling | `cf scale -i N`: consumers are in-process, so more instances means more concurrent consumers on the shared queue (watch DB pool size per instance) |
| Rollback | `cloud-foundry/scripts/rollback.sh APP <revision>` wrapping `cf rollback --strategy rolling`; or droplet pinning; or re-map the blue route |
| Smoke | `cloud-foundry/scripts/smoke.sh` asserts liveness, readiness and that unauthenticated `/api/v1/me` is 401 |

Scripts: `deploy.sh` (validate manifests, rolling push, smoke), `smoke.sh`, `blue-green.sh`, `rollback.sh`.

## 17.4 Environments and profiles

{{diagram:profiles}}

## 17.5 Cloud Foundry versus Kubernetes in one table

| Concern | Cloud Foundry | Kubernetes |
|---|---|---|
| Unit of deploy | App plus buildpack | Container image plus manifests |
| Learning curve for app teams | Low | Higher |
| Scaling | `cf scale`, platform autoscaler | HPA, custom metrics (KEDA option) |
| Health | `health-check-type: http` | Liveness, readiness and startup probes |
| Network policy | Application security groups | `NetworkPolicy` |
| Secrets | CredHub or user-provided service | `Secret`, external secret operator |
| Portability | Platform specific | Broad |

## Reference material

{{embed:docs/operations/cloud-foundry-guide.md|title=Cloud Foundry guide}}

{{embed:docs/adr/0008-cloud-foundry-vs-kubernetes.md|title=ADR-0008: Cloud Foundry versus Kubernetes}}
