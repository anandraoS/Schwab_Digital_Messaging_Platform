# Cloud and DevOps Deep Dive: Docker, Cloud Foundry, Kubernetes, CI/CD

Scope note: this is a reference chapter for a senior Java full-stack messaging role interview. The repository assets referenced here (`infrastructure/docker`, `infrastructure/kubernetes`, `infrastructure/helm`, `cloud-foundry/`, `Jenkinsfile`, `bamboo-specs/`, `.github/workflows`) are **statically validated only and NOT executed** (CONTRACT section 0 and section 12). No Docker build, compose up, Helm render, kubectl apply, `cf push`, or CI run was performed. The snippets below are illustrative teaching examples, not copies of repo files.

## The Analogy Corner

- **Docker image = a lunchbox, container = lunch being eaten.** The image is a sealed lunchbox with everything packed in layers (sandwich, fruit, juice). Packing the rarely changing things first means that when you only change the juice, you do not repack the whole box.
- **Kubernetes = a school cafeteria manager.** You say "I need 3 lunch ladies at all times". If one goes home sick, the manager calls a replacement. If lunchtime gets busy, the manager adds more ladies (HPA). Before a new lady serves, the manager checks she has washed her hands (readiness probe).
- **Blue-green = two stages in a theatre.** The audience watches stage Blue. Behind the curtain you build the new show on stage Green, rehearse it, then swing the spotlight. If the new show flops, swing the spotlight back.
- **Graceful shutdown = closing a shop.** You turn the sign to "closed", serve the customers already inside, then lock the door. You do not shove people out mid-purchase.

## Twelve-Factor Mapping for a Messaging Platform

| Factor | Application to the platform |
|---|---|
| Codebase | One repo, one deployable `messaging-platform` Maven module (CONTRACT section 1) |
| Dependencies | Declared in `backend/pom.xml`; Spring Boot BOM pins versions |
| Config | `messaging.*` keys driven by env vars per profile, no secrets in files (CONTRACT section 9) |
| Backing services | PostgreSQL, RabbitMQ, Redis, MongoDB are attached resources selected by `messaging.bus.type` and `messaging.coordination.type` |
| Build/release/run | Separate image build, immutable tag, environment-specific config at release |
| Processes | Stateless API and consumer; state lives in DB/queue; idempotent consumer makes restarts safe |
| Port binding | Embedded server, port from `SERVER_PORT` |
| Concurrency | Scale out by replicas; bounded thread pools inside (`consumer.concurrency`) |
| Disposability | Fast start, graceful shutdown with 30 s drain (CONTRACT section 6) |
| Dev/prod parity | `local`/`test` profiles use H2 and in-memory adapters; `prod-like` uses real services. Honest gap: the parity is behavioral (same port interfaces), not identical engines, so Testcontainers `*ContainerIT` tests exist for the real ones |
| Logs | Write structured JSON to stdout; platform collects |
| Admin processes | Flyway migrations and batch jobs as one-off tasks |

## Docker

### Multi-stage build

A multi-stage build compiles in a fat JDK image and ships only the runtime. Benefits: smaller image, smaller attack surface, no build tools or source in production.

```dockerfile
# Stage 1: build (illustrative)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN ./mvnw -B -DskipTests package

# Stage 2: extract Spring Boot layers
FROM eclipse-temurin:21-jdk AS layers
WORKDIR /layers
COPY --from=build /workspace/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Stage 3: runtime
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

### Layer caching rules

1. Order instructions from least to most frequently changing: base image, dependency descriptor, dependencies, application classes.
2. Spring Boot layered jars put third-party dependencies in a separate layer from your classes, so a code-only change rebuilds one small layer.
3. Use a `.dockerignore` to keep `target/`, `.git`, and local env files out of the build context.
4. Pin base image by digest in regulated environments for reproducibility.

### Non-root and hardening

- `USER app` (numeric UID is better for Kubernetes `runAsNonRoot` checks).
- Read-only root filesystem with an `emptyDir` for `/tmp`.
- Drop all Linux capabilities; no privilege escalation.
- Scan images in CI (Trivy, Grype, or an enterprise scanner) and fail on critical CVEs.
- Never bake secrets into layers: layers are permanent and inspectable even if a later layer deletes the file.

### JVM in containers

Java 21 is container-aware. Use `-XX:MaxRAMPercentage` instead of a hard `-Xmx` so heap tracks the memory limit, leaving headroom for metaspace, thread stacks, and direct buffers. A frequent interview pitfall: setting the container memory limit equal to heap leads to OOMKilled pods because non-heap memory exceeds the remainder.

## Cloud Foundry (PCF-style)

Cloud Foundry is a PaaS: you push an artifact, the platform stages it with a buildpack into a droplet, and runs it in Diego cells. You do not write a Dockerfile.

### manifest.yml

```yaml
# Illustrative manifest, placeholders only
applications:
  - name: messaging-platform
    path: backend/target/messaging-platform.jar
    instances: 2
    memory: 1G
    disk_quota: 1G
    health-check-type: http
    health-check-http-endpoint: /actuator/health/readiness
    timeout: 120
    buildpacks:
      - java_buildpack
    env:
      SPRING_PROFILES_ACTIVE: prod-like
      JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
    services:
      - messaging-postgres
      - messaging-rabbit
      - messaging-redis
    routes:
      - route: messaging-platform.apps.example.test
```

### Key concepts

| Concept | Meaning |
|---|---|
| Buildpack | Detects app type, adds JRE and runtime config; Java buildpack sets memory settings from `memory` |
| Droplet | Staged, runnable artifact; same droplet can be promoted between spaces |
| Service binding | `VCAP_SERVICES` env var injects credentials; Spring Cloud Connectors or the Java buildpack auto-reconfiguration can map them. Prefer explicit config mapping so behavior is visible |
| Org/space | Isolation and RBAC boundary (dev, test, prod spaces) |
| Routes | Hostname mapped to the app; multiple apps can share a route, which enables blue-green |
| Health check | `http` type is better than the default `port` check because it verifies the app is functional |

### Blue-green with the cf CLI

```bash
# Deploy the new version beside the old one (placeholders)
cf push messaging-platform-green -f manifest.yml --no-route
cf map-route messaging-platform-green apps.example.test --hostname messaging-platform-tmp
# Smoke test the temporary route, then switch traffic
cf map-route messaging-platform-green apps.example.test --hostname messaging-platform
cf unmap-route messaging-platform-blue apps.example.test --hostname messaging-platform
# Keep blue stopped but available for fast rollback
cf stop messaging-platform-blue
```

Alternative: `cf push --strategy rolling` performs a rolling deployment natively on recent CF versions. The blue-green pattern remains useful when you want an explicit smoke-test window and instant rollback by re-mapping the route.

Database caveat: blue and green share one database, so migrations must be **backward compatible** (expand, then contract). Flyway scripts that drop a column used by blue break the rollback path. This applies identically on Kubernetes.

Messaging caveat: both versions may consume from the same queues during the overlap. Because the consumer is idempotent (`processed_message`, CAS status update, CONTRACT section 6), overlap is safe, but envelope schema changes must be tolerant (ignore unknown fields).

## Kubernetes

### Deployment, Service, probes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: messaging-api
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels: { app: messaging-api }
  template:
    metadata:
      labels: { app: messaging-api }
    spec:
      terminationGracePeriodSeconds: 45
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
      containers:
        - name: api
          image: registry.example.test/messaging-platform:1.0.0
          ports: [{ containerPort: 8080 }]
          envFrom:
            - configMapRef: { name: messaging-config }
            - secretRef: { name: messaging-secrets }
          resources:
            requests: { cpu: "500m", memory: "768Mi" }
            limits: { memory: "1Gi" }
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            failureThreshold: 30
            periodSeconds: 5
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            periodSeconds: 20
          lifecycle:
            preStop:
              exec: { command: ["sleep", "5"] }
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }
---
apiVersion: v1
kind: Service
metadata:
  name: messaging-api
spec:
  selector: { app: messaging-api }
  ports: [{ port: 80, targetPort: 8080 }]
```

### Probes: what each one should check

| Probe | Question | Failure action | Pitfall |
|---|---|---|---|
| Startup | Has the app finished booting (Flyway, context)? | Keep waiting, then restart | Without it, a slow start trips liveness and causes restart loops |
| Readiness | Can I take traffic now? | Remove from Service endpoints | Do not include flaky downstream dependencies like a provider, or one outage removes every pod and amplifies it |
| Liveness | Is the process wedged beyond recovery? | Restart container | Never tie to the database; a DB blip would restart the whole fleet |

For this platform, readiness should reflect "can accept and persist a submission" (DB reachable), while provider health is surfaced by circuit breakers and metrics, not probes. When consumers are paused (`/ops/consumers/pause`) the API pod is still ready.

### ConfigMap, Secret, and their limits

- ConfigMap holds non-sensitive `messaging.*` settings, injected as env vars or files.
- Secret holds the DB password, Rabbit credentials, callback HMAC secrets. Kubernetes Secrets are base64-encoded, **not encrypted** by default. Enable etcd encryption at rest, restrict RBAC, and prefer an external manager (Vault, cloud secret manager) with CSI driver or External Secrets operator.
- Env-var secrets leak into crash dumps and `docker inspect`-style tooling; mounted files are slightly safer and allow rotation without restart if the app re-reads them.
- Changing a ConfigMap does not restart pods. Use a checksum annotation in Helm (`checksum/config`) to trigger a rollout.

### HPA and consumer scaling

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: messaging-api
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: messaging-api }
  minReplicas: 3
  maxReplicas: 12
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 65 }
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
```

CPU is a decent signal for the API. For consumers, CPU lags reality: queue depth is the better signal. With KEDA or a Prometheus adapter you can scale on `messaging.queue.depth` (CONTRACT section 8). Caveats: more consumers means more concurrent provider calls, so provider rate limits (token bucket) and database pool size (`hikaricp_connections_*`) cap the useful replica count. Scaling beyond that only moves the bottleneck.

### PodDisruptionBudget

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: messaging-api
spec:
  minAvailable: 2
  selector:
    matchLabels: { app: messaging-api }
```

A PDB constrains voluntary disruptions (node drains, upgrades), not crashes. Pair it with topology spread constraints so replicas land on different nodes and zones.

### Rolling update mechanics

With `maxUnavailable: 0` and `maxSurge: 1`, Kubernetes starts a new pod, waits for readiness, then terminates an old one. Rollback: `kubectl rollout undo deployment/messaging-api` (revert to previous ReplicaSet). Helm: `helm rollback <release> <revision>`. Both are fast, but neither reverts database changes, hence expand/contract migrations.

## Graceful Shutdown End to End

Sequence on pod termination:

1. Pod marked Terminating; endpoint removal and SIGTERM race each other. The `preStop` sleep gives load balancers time to stop routing.
2. SIGTERM reaches the JVM. Spring Boot with `server.shutdown=graceful` stops accepting new HTTP requests and waits for active ones (`spring.lifecycle.timeout-per-shutdown-phase`).
3. Consumer `SmartLifecycle` stops pulling, finishes in-flight work within 30 s, and unacknowledged envelopes return to the queue (CONTRACT section 6).
4. If the process exceeds `terminationGracePeriodSeconds`, SIGKILL arrives. The platform tolerates this: the attempt row is written `STARTED` before the provider call, and `StuckMessageSweeper` converts stale `PROCESSING` into `DELIVERY_UNKNOWN`, which then goes to reconciliation instead of a blind retry.

Rule of thumb: `terminationGracePeriodSeconds` > preStop sleep + shutdown timeout (here 5 + 30 < 45).

## CI/CD

### Pipeline stages

| Stage | Purpose | Gate |
|---|---|---|
| Checkout and build | Compile, unit tests | Fail fast |
| Static analysis | Linting, SAST, dependency scan (SCA), secret scan | No critical findings |
| Integration tests | `@SpringBootTest` on profile `test`; Testcontainers profile `-Pcontainers` when Docker is available | Green |
| Package and scan image | Build image, scan, sign, push with immutable tag (git SHA) | No critical CVEs |
| Deploy to dev/test | Automated | Smoke test passes |
| Contract/E2E/perf | API and end-to-end checks; k6 on a schedule | SLO thresholds |
| Promote to prod | Manual approval for regulated flows | Change record, approver |
| Post-deploy verification | Watch error rate and burn rate for a bake period | Auto-rollback trigger |

Principle: **build once, promote the same artifact**. Rebuilding per environment breaks traceability.

### Jenkinsfile (declarative, illustrative)

```groovy
pipeline {
  agent any
  options { timestamps(); timeout(time: 45, unit: 'MINUTES'); disableConcurrentBuilds() }
  environment {
    IMAGE = "registry.example.test/messaging-platform:${env.GIT_COMMIT}"
  }
  stages {
    stage('Build and test') {
      steps { sh './mvnw -B verify' }
      post { always { junit '**/target/surefire-reports/*.xml' } }
    }
    stage('Scan') {
      steps { sh './scripts/scan-dependencies.sh' }
    }
    stage('Image') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'registry-creds',
                         usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
          sh './scripts/build-and-push.sh "$IMAGE"'
        }
      }
    }
    stage('Deploy to test') { steps { sh './scripts/deploy.sh test "$IMAGE"' } }
    stage('Approve prod') {
      when { branch 'main' }
      steps { input message: 'Promote to production?', submitter: 'release-managers' }
    }
    stage('Deploy to prod') {
      when { branch 'main' }
      steps { sh './scripts/deploy.sh prod "$IMAGE"' }
    }
  }
  post { failure { echo 'Notify the team channel' } }
}
```

Interview notes: use credential bindings so Jenkins masks values in logs; keep logic in scripts so it can be tested outside Jenkins; avoid `echo` of environment; use shared libraries for reuse; run agents as ephemeral pods to avoid state leaks.

### Bamboo specs (Java or YAML)

Bamboo (Atlassian) supports "plans as code" via Bamboo Specs. Concepts: Project, Plan, Stage, Job, Task; deployment projects and environments are separate from build plans. Bamboo stores secrets as encrypted variables and masks variables whose names contain `password` or `secret`. Equivalent structure to the Jenkins flow above: build/test job, artifact sharing to a deployment project, environment-specific variables, manual approval before the production environment.

```yaml
# Illustrative Bamboo YAML spec
version: 2
plan:
  project-key: MSG
  key: BUILD
  name: Messaging Platform Build
stages:
  - Build:
      jobs: [Build and test]
Build and test:
  tasks:
    - checkout
    - script:
        - ./mvnw -B verify
  artifacts:
    - name: jar
      pattern: backend/target/*.jar
```

### GitHub Actions

```yaml
name: ci
on:
  pull_request:
  push: { branches: [main] }
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -B verify
```

Security practices: set least-privilege `permissions`, pin third-party actions to commit SHAs, use environments with required reviewers for production, and prefer OIDC federation to cloud providers over long-lived keys. Do not run untrusted fork PRs with secrets (`pull_request_target` is a classic foot-gun).

### Secrets in CI

| Rule | Reason |
|---|---|
| Store in the CI secret store or an external vault, never in the repo | Git history is forever |
| Scope per environment and per pipeline | Limit blast radius |
| Short-lived credentials via OIDC where possible | Less to rotate and leak |
| Mask in logs, never `set -x` around secrets | Logs are widely readable |
| Run secret scanners on every push | Catch mistakes early |
| Rotate on suspected exposure | Assume compromise |
| Use dummy values in tests (CONTRACT section 9) | Tests must not need real secrets |

## Rollback Strategies

| Strategy | Speed | Cost | Notes |
|---|---|---|---|
| Redeploy previous image (K8s rollout undo, Helm rollback) | Fast | Low | Needs backward-compatible schema |
| Blue-green route swap | Instant | Double capacity during overlap | Best for explicit smoke gate |
| Canary with automatic abort | Gradual | Needs traffic shaping and metrics | Abort on burn rate |
| Feature flag off | Instant | Needs flag discipline | Decouples deploy from release |
| Roll forward fix | Medium | Risky under pressure | Acceptable when rollback is unsafe (schema) |

Messaging-specific rollback considerations: messages in flight keep their pinned template version (ADR-0007 per CONTRACT section 5), so a rollback of code does not change content already pinned. Envelope compatibility matters: if the new version adds fields, the old version must ignore them. Consider pausing consumers (`/ops/consumers/pause`) while verifying a risky release, since messages are durable in the queue.

## Interview Q and A Snippets

**Q: Liveness vs readiness for a service that depends on a flaky provider?** Readiness and liveness should not depend on the provider. Use a circuit breaker and metrics. Otherwise one external outage drains every pod.

**Q: How do you do zero-downtime deploys with a DB change?** Expand and contract: add nullable column in release N, write both, backfill, switch reads in N+1, drop in N+2.

**Q: How do you stop duplicate sends during a rolling update?** At-least-once transport plus idempotent consumer (dedupe table, CAS transition) plus provider idempotency reference (CONTRACT section 6). Duplicates are tolerated by design; they are not prevented by deployment ordering.

**Q: Why not just `kubectl apply` from a laptop?** No audit, drift, unreviewed changes. Use pipelines or GitOps (Argo CD or Flux) where Git is the source of truth.

## Key takeaways

- Multi-stage, layered, non-root images with no baked secrets; order layers by change frequency.
- Size JVM memory as a percentage of the container limit and leave non-heap headroom.
- Cloud Foundry: manifest plus buildpack plus bound services; blue-green via route mapping; prefer HTTP health checks.
- Kubernetes: startup, readiness, and liveness probes answer different questions; never couple them to flaky downstreams.
- Scale consumers on queue depth, but cap by provider rate limits and DB pool size.
- Graceful shutdown budget: preStop plus drain timeout must fit inside the termination grace period; crash safety comes from idempotency and the stuck-message sweeper.
- Build once, promote the same immutable artifact; keep pipeline logic in scripts; manual gate before production where required.
- Rollback is only as safe as your schema compatibility: use expand and contract.
- Secrets live in a vault or CI secret store, are scoped and short-lived, and are never printed.
- Be honest: all Docker, K8s, CF, and CI assets in this repository are statically validated only and were not executed.
