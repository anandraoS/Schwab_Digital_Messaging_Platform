# Question Bank 5: PCF / Cloud Foundry, Kubernetes, Docker, CI/CD and Security (Q95-Q118)

Reference-implementation study material. Docker, Kubernetes, Cloud Foundry and CI assets in this project are statically validated only and were not executed against real clusters (CONTRACT section 0 and 12). Security design cites CONTRACT sections 1 and 5.

## Cloud Platforms, Containers and CI/CD

### Q95. How would you deploy a Spring Boot application to PCF and to Kubernetes?
**Tags:** Cloud Platforms, Senior, Scenario
**Analogy:** Cloud Foundry is a furnished apartment where you bring your suitcase (the jar) and the building supplies the rest; Kubernetes is a plot of land with a toolbox where you decide the walls, doors and plumbing yourself.
**Model answer:**
Clarify: the target environments, existing platform standards, stateful dependencies (PostgreSQL, RabbitMQ, Redis, Mongo), secrets handling, scaling needs, and release strategy.

Common prerequisites (twelve-factor): one build artifact, config from environment, stateless processes, logs to stdout, health endpoints (Actuator), graceful shutdown (`server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase`), no dev endpoints in production profiles (CONTRACT section 9).

Cloud Foundry (PCF/Tanzu):
- Build the jar; `manifest.yml` declares name, memory (for example 1G), instances, `buildpacks: [java_buildpack]`, `JBP_CONFIG_OPEN_JDK_JRE` for Java 21, environment, routes, `health-check-type: http` with `/actuator/health/readiness`, and bound services (`services:`).
- Services via the marketplace or user-provided services; Spring auto-reconfiguration or `java-cfenv` reads `VCAP_SERVICES`; secrets come from bound services or CredHub, never the manifest.
- `cf push` (rolling with `--strategy rolling`) or blue-green with a second app and route swap; `cf scale`, `cf logs`, `cf restage` on stack updates.
- The buildpack's memory calculator sets heap from the container memory.

Kubernetes:
- Multi-stage Dockerfile, non-root user, layered jar; push to a registry.
- Manifests or Helm chart: `Deployment` (replicas, resources requests/limits, rolling update `maxUnavailable: 0`), `Service`, `Ingress`, `ConfigMap`, `Secret` (or external secrets), liveness/readiness/startup probes, `HorizontalPodAutoscaler`, `PodDisruptionBudget`, `terminationGracePeriodSeconds` greater than the app's shutdown time, `securityContext` (non-root, read-only root filesystem).
- Database migrations as a separate job or init step.

Honesty: the repository's Docker/K8s/CF files are validated statically and were not applied (CONTRACT section 0).

Measure: deploy duration, rollback time, error rate during rollout, resource utilization.

<!-- in-project:b31 -->
> [!NOTE] In this project
> **File:** `cloud-foundry/manifest.yml`
>
> Deploy command in the file header: `cf push -f cloud-foundry/manifest.yml --vars-file cloud-foundry/vars/dev.yml --strategy rolling`. Secrets are not in the manifest. The manifest is validated by `cloud-foundry/validate-manifest.mjs` only; no CF foundation was available, so nothing was pushed.
>
> ```yaml
> applications:
>   - name: messaging-backend-((env))
>     buildpacks:
>       - java_buildpack
>     instances: ((instances))
>     memory: ((memory))
>     health-check-type: http
>     health-check-http-endpoint: /actuator/health/liveness
>     env:
>       SPRING_PROFILES_ACTIVE: prod-like,rabbit,cloud
>       JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
>       MESSAGING_SECURITY_DEV_TOKEN_ENABLED: 'false'
>       SERVER_SHUTDOWN: graceful
>     services: [messaging-postgres, messaging-rabbit, messaging-redis, messaging-mongo]
> ```

**Follow-ups:**
- How does config differ between CF and K8s?
- What does the buildpack do that your Dockerfile must do?
- How do you roll back on each platform?
**Pitfalls:**
- Baking config or secrets into the artifact.
- No readiness probe or health check.
- Setting heap equal to container memory.

### Q96. Compare Cloud Foundry and Kubernetes. When would you pick each?
**Tags:** Cloud Platforms, Senior, Concept
**Analogy:** CF is a hotel with a front desk (push and go); Kubernetes is a construction kit that can build a hotel, a factory or a city, but you assemble it.
**Model answer:**
Cloud Foundry (application platform, PaaS): developer-centric abstraction: `cf push` an app; the platform detects the buildpack, stages a droplet, schedules containers (Diego), routes traffic, injects services (`VCAP_SERVICES`), aggregates logs (Loggregator). Opinionated, fast to onboard, standardized security and operations; limited customization, best for 12-factor stateless apps. Scaling via instance count; autoscaler add-on.

Kubernetes (container orchestrator): you package containers and declare desired state with resources (Deployment, StatefulSet, Job, CronJob, Service, Ingress, ConfigMap, Secret, HPA, NetworkPolicy). Extremely flexible: sidecars, operators, custom resources, stateful workloads, batch jobs, service mesh. Higher operational burden and learning curve; platform teams often build an internal developer platform on top.

Comparison points:
- Unit of deployment: source/jar plus buildpack (CF) versus container image (K8s).
- Config and secrets: environment and bound services versus ConfigMaps/Secrets/external secret stores.
- Networking: gorouter and route mapping, container-to-container policies versus Services, Ingress/Gateway API, NetworkPolicies, mesh.
- Health: health checks, restart on failure (both); K8s has separate liveness, readiness, startup probes.
- Zero-downtime: CF rolling or blue-green; K8s rolling, canary via mesh or progressive delivery tools.
- Batch and stateful: K8s is stronger (Jobs, StatefulSets, volume claims); CF handles tasks (`cf run-task`).
- Portability and ecosystem: K8s is the de facto standard across clouds.

Choice: if an organization already operates CF and the app is a stateless Spring Boot service, CF gives speed and consistency. If workloads are heterogeneous, need sidecars, operators, custom scaling (KEDA on queue depth) or multi-cloud portability, choose Kubernetes. Many enterprises migrate from CF to K8s gradually; design the app to be platform-neutral to support both (CONTRACT section 11 includes both `cloud-foundry/` and `infrastructure/kubernetes`).

Measure: onboarding time, deploy frequency, platform incident load.
**Follow-ups:**
- What does a buildpack replace?
- How do you handle scheduled jobs on each?
- What is the equivalent of a CF service binding in K8s?
**Pitfalls:**
- Treating K8s as always better.
- Writing platform-specific code in the application.
- Ignoring operational cost.

### Q97. Describe a blue-green deployment on Cloud Foundry and how you would roll back.
**Tags:** Cloud Platforms, Intermediate, Scenario
**Analogy:** Two identical stages: the audience watches the old one while you rehearse on the new one, then you swing the spotlight across; if the show flops, swing it back.
**Model answer:**
Clarify: is the service stateless, does the database schema change, and how long may two versions coexist?

Classic blue-green with the `cf` CLI:
1. Push the new version under a different name with a temporary route: `cf push messaging-api-green -f manifest.yml --no-route` then `cf map-route messaging-api-green apps.example.test --hostname messaging-tmp`.
2. Smoke test the temporary route: health, a synthetic message through the simulator path, key metrics.
3. Map the production route to green: `cf map-route messaging-api-green apps.example.test --hostname messaging`. Both blue and green now take traffic.
4. Unmap blue: `cf unmap-route messaging-api-blue ...`; keep blue running (stopped or at low instances) for the rollback window.
5. Rollback: re-map the route to blue and unmap green (seconds). After the window, delete blue and rename green.

Modern alternative: `cf push --strategy rolling` (instances replaced gradually) or the built-in canary-style strategy; blue-green gives the cleanest instant rollback.

Gotchas:
- Schema compatibility: both versions run against the same database, so migrations must be backward compatible (expand-and-contract).
- Queue consumers: both versions consume the same queue during overlap; processing must be idempotent and envelope formats compatible.
- Scheduled pollers and sweepers running in both versions are protected by compare-and-set claims (CONTRACT section 2).
- In-flight requests: drain via `cf unmap-route` then wait; graceful shutdown completes processing.
- Sticky state: none expected in stateless design.
- Secrets/service bindings must be bound to the green app.

Automation: do it in a pipeline stage (with the CF CLI and a deployer credential in the secret store), including verification gates and automatic rollback on failed smoke tests. These steps are documented, not executed here.

Measure: error rate and latency by version during the overlap, time to rollback.
**Follow-ups:**
- How is a rolling push different?
- What happens to the message consumers during overlap?
- How do you handle a breaking DB migration?
**Pitfalls:**
- Breaking schema changes with blue-green.
- Deleting blue immediately.
- Forgetting service bindings on green.

### Q98. Explain Kubernetes liveness, readiness and startup probes, and graceful shutdown for a message consumer.
**Tags:** Kubernetes, Intermediate, Concept
**Analogy:** Readiness asks "are you ready to serve customers?", liveness asks "are you still alive or should we replace you?", and startup says "give the new hire time to find their desk before judging".
**Model answer:**
- Readiness probe: if it fails, the pod is removed from Service endpoints (no traffic) but not restarted. Use for "can I take traffic": database reachable, broker connected, not shutting down. Spring Boot: `/actuator/health/readiness` (`management.endpoint.health.probes.enabled=true`).
- Liveness probe: if it fails, the kubelet restarts the container. Keep it simple and internal (the process is not deadlocked); do not include external dependencies like the database, otherwise a DB outage restarts every pod and worsens it. Spring: `/actuator/health/liveness`.
- Startup probe: protects slow starting apps; liveness and readiness are disabled until startup succeeds, avoiding premature kills during Flyway migrations or cache warm-up.

Probe tuning: `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, `failureThreshold`; timeouts shorter than periods; avoid aggressive thresholds that cause flapping under GC pauses.

Graceful shutdown (CONTRACT section 6): on SIGTERM, Kubernetes removes the pod from endpoints (asynchronously) and waits `terminationGracePeriodSeconds` (default 30) before SIGKILL. For the consumer: Spring `server.shutdown=graceful`; the `SmartLifecycle` bean stops consuming, finishes in-flight work (30 s timeout), unacked messages return to the queue. Set `terminationGracePeriodSeconds` above the app timeout (for example 45 to 60 s). A `preStop` sleep (5 to 10 s) lets endpoint removal propagate so the load balancer stops sending new requests before the app starts refusing.

Bulk jobs: long-running batch runs belong in separate worker deployments or Jobs with larger grace periods, so a rolling update does not kill them mid-chunk (restartability makes this safe but slower).

Measure: restarts, readiness flaps, in-flight completions at shutdown, request errors during rollouts.

<!-- in-project:b33 -->
> [!NOTE] In this project
> **File:** `infrastructure/kubernetes/base/deployment.yaml`
>
> The preStop sleep lets endpoints and ingress stop routing before SIGTERM starts Spring graceful shutdown (`SERVER_SHUTDOWN=graceful`, consumers drained within 30 s). Manifests were statically validated only; no cluster was available.
>
> ```yaml
> terminationGracePeriodSeconds: 60   # > app drain timeout (30s) + preStop sleep
> # ...
> startupProbe:
>   httpGet: { path: /actuator/health/liveness, port: http }
>   periodSeconds: 5
>   failureThreshold: 36        # up to 3 minutes to start (Flyway + JPA)
> livenessProbe:
>   httpGet: { path: /actuator/health/liveness, port: http }
> readinessProbe:
>   httpGet: { path: /actuator/health/readiness, port: http }
> lifecycle:
>   preStop:
>     exec:
>       command: ["sh", "-c", "sleep 10"]
> ```

**Follow-ups:**
- Why not check the database in the liveness probe?
- What does a startup probe fix?
- What is the effect of `preStop`?
**Pitfalls:**
- Same endpoint for liveness and readiness including dependencies.
- Grace period shorter than the shutdown time.
- No `preStop` and lost requests during rollouts.

### Q99. After a deployment, new pods fail readiness and old pods keep serving. Walk through your troubleshooting.
**Tags:** Kubernetes, Senior, Scenario
**Analogy:** New staff arrive but the door checker keeps turning them away; you check what the checker looks for before blaming the staff.
**Model answer:**
Clarify: what changed (image, config, secrets, migration, resource limits), and the pod state: `Pending`, `ContainerCreating`, `CrashLoopBackOff`, `Running` but not ready.

Systematic checks:
1. `kubectl get pods`, `kubectl describe pod`: events show scheduling failures (insufficient CPU/memory, node selectors), image pull errors, failed probes, OOMKilled, config errors (missing Secret/ConfigMap keys).
2. `kubectl logs <pod> [--previous]`: startup exceptions: Flyway migration failure (lock held, checksum mismatch, SQL error), bean wiring errors, invalid property binding, cannot reach DB/RabbitMQ (wrong host, credentials, NetworkPolicy), invalid JWT issuer URI.
3. Probes: wrong path or port, probe timeout too short for slow startup (use a startup probe), readiness dependent on an unavailable dependency (health group configured with broker or Redis, which should be optional).
4. Resources: limits too low for JVM (OOMKilled during startup, CPU throttling making start take minutes).
5. Rollout: `kubectl rollout status`, `kubectl rollout history`; with `maxUnavailable: 0` the old pods continue serving, which is the intended safety.

Actions: roll back (`kubectl rollout undo deployment/messaging-api`) if the cause is not quickly fixable; fix config and redeploy; if a migration failed halfway, check the Flyway schema history table and fix forward (never edit an applied script).

Prevention: pre-deploy config validation (`@ConfigurationProperties` validation fails fast), staging deployment with the same manifests, readiness that includes only essential dependencies, startup probe, migration as a separate pipeline job, canary rollout.

Measure: time to detect via rollout failure alert, time to rollback, percentage of failed rollouts.
**Follow-ups:**
- When is `CrashLoopBackOff` caused by liveness probes?
- How do you debug without a shell in a distroless image?
- How do you avoid migration conflicts across replicas?
**Pitfalls:**
- Deleting pods repeatedly instead of reading events.
- Loosening probes to hide the problem.
- Editing applied migrations.

### Q100. How would you autoscale message consumers, and why is CPU a poor signal alone?
**Tags:** Kubernetes, Senior, Scenario
**Analogy:** Hiring more cashiers based on the length of the queue rather than how tired the current cashiers look.
**Model answer:**
Clarify: workload (I/O-bound consumers waiting on providers), scaling goals (keep queue age under N seconds), constraints (provider rate limits, DB capacity), and cost.

Consumers are I/O-bound: CPU stays low while threads wait on providers, so CPU-based HPA under-scales while the queue grows. Better signals:
- Queue depth per consumer, or message age (oldest message): KEDA (Kubernetes Event-driven Autoscaling) with a RabbitMQ scaler, or HPA with external metrics (Prometheus adapter on `messaging.queue.depth{priority}` and age).
- Desired replicas = ceil(backlog / (rate per pod x target drain time)). Example: 60,000 queued, each pod handles 20 msg/s, target drain 5 minutes (300 s): need 60,000 / (20 x 300) = 10 pods.
- Scale on rate of incoming messages too, to scale ahead of backlog.

Limits and guards:
- Max replicas bounded by the provider's rate limit (the token bucket caps global throughput, so extra pods merely idle) and DB connection capacity (replicas x Hikari pool must stay under DB limits).
- Scale-down stabilization (for example 5 minutes) avoids flapping; graceful shutdown requeues unacked work.
- Min replicas of 2 or more across zones for availability; PodDisruptionBudget.
- Separate deployments for API, consumers, and batch workers with independent scaling.
- Cold start time (JVM) argues for headroom and for startup optimization (CDS, lazy init, readiness).

Scaling the consumer does not help when the bottleneck is the provider or the database; check those first (see Q119).

Measure: time to drain after spikes, replica count versus backlog, provider 429 rate, DB pool saturation, scaling oscillations.

<!-- in-project:b34 -->
> [!NOTE] In this project
> **File:** `infrastructure/kubernetes/base/hpa.yaml`
>
> CPU alone is a poor signal for I/O-bound consumers, so the example adds queue depth as an external metric. The file itself warns that the External block must be removed if no external-metrics provider is installed.
>
> ```yaml
> minReplicas: 2
> maxReplicas: 8
> behavior:
>   scaleDown: { stabilizationWindowSeconds: 300, policies: [{ type: Pods, value: 1, periodSeconds: 60 }] }
>   scaleUp:   { stabilizationWindowSeconds: 30,  policies: [{ type: Pods, value: 2, periodSeconds: 60 }] }
> metrics:
>   - type: Resource
>     resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
>   - type: External          # needs prometheus-adapter or KEDA
>     external:
>       metric: { name: messaging_queue_depth }
>       target: { type: AverageValue, averageValue: "2000" }
> ```

**Follow-ups:**
- Why does the provider limiter make over-scaling pointless?
- How do you scale to zero safely?
- What metric would you use for the API pods?
**Pitfalls:**
- CPU-only HPA for I/O-bound workers.
- No upper bound.
- Flapping due to no stabilization window.

### Q101. What makes a good production Dockerfile for a Spring Boot service?
**Tags:** Docker, Intermediate, Concept
**Analogy:** Packing a lunchbox: only the food you need, nothing that spoils, sealed so it is safe to carry.
**Model answer:**
Goals: small, reproducible, secure, fast to build and start.

Practices:
- Multi-stage build: stage 1 builds with Maven/JDK; stage 2 copies only the result into a slim runtime image (a JRE image, `eclipse-temurin:21-jre`, or distroless). No build tools or source in the final image.
- Layered jars (Spring Boot `layertools` or `-Djarmode=tools extract`) so dependencies sit in a cached layer and only application classes change frequently; faster builds and pulls.
- Run as non-root (`USER 10001`), read-only root filesystem where possible, drop Linux capabilities; no SSH, no package managers in the runtime image.
- Pin base image versions or digests; scan images (Trivy, Grype or registry scanning) in CI; rebuild regularly for security patches.
- Do not bake secrets or environment-specific config; pass via environment or mounted secrets. Use `.dockerignore` to exclude `.git`, `target`, local env files.
- JVM in containers: use container-aware defaults and `-XX:MaxRAMPercentage=70` rather than fixed `-Xmx`; `-XX:+ExitOnOutOfMemoryError`; set `JAVA_TOOL_OPTIONS` for flags.
- `ENTRYPOINT` in exec form so signals reach the JVM (graceful shutdown); avoid shell wrappers that swallow SIGTERM or use `exec`.
- `HEALTHCHECK` is optional on Kubernetes (probes cover it) but helpful on plain Docker.
- One process per container; logs to stdout.
- Labels (OCI metadata) with commit sha and build time; tag images immutably (commit sha), not just `latest`.
- Alternative: Cloud Native Buildpacks (`spring-boot:build-image`) produce layered, optimized images without a Dockerfile.

Honesty: in this project's environment Docker is not available, so Docker assets are statically reviewed only (CONTRACT section 0).

Measure: image size, build time, vulnerability count, startup time.

<!-- in-project:b30 -->
> [!NOTE] In this project
> **File:** `infrastructure/docker/Dockerfile.backend`
>
> Multi-stage build (Maven image, then a JRE image), non-root UID 10001, `tini` as PID 1, `exec java` so SIGTERM reaches the JVM for graceful shutdown. The image was NOT built here: Docker was not available.
>
> ```dockerfile
> USER 10001:10001
> # Container-aware JVM flags. Override with JAVA_TOOL_OPTIONS / JAVA_OPTS at runtime.
> ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=50 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
> HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 ...
> ENTRYPOINT ["/usr/bin/tini", "--", "sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
> ```

**Follow-ups:**
- Why does `exec` form matter?
- How would you reduce startup time (CDS, AOT)?
- Distroless trade-offs?
**Pitfalls:**
- Running as root.
- Using `latest`.
- Secrets copied into layers (they persist in history).

### Q102. A security scan finds critical CVEs in the base image and a developer accidentally committed an API key into a Dockerfile layer. What do you do?
**Tags:** Docker, Senior, Scenario
**Analogy:** Finding both an expired ingredient in the pantry and a house key taped to the lunchbox: replace the ingredient, but first change the locks.
**Model answer:**
Clarify: is the key real and was the image or repository pushed to a shared registry, who can pull it, and which CVEs are reachable (a library in the image that the app never calls versus a remotely exploitable flaw)?

Secret exposure (highest priority, treat as compromised):
1. Revoke and rotate the key immediately at the issuer; do not wait for cleanup. Check audit logs for misuse since the commit time.
2. Remove it from the repository history (git filter-repo/BFG) and rotate anyway: deleting does not undo exposure, because clones and image layers retain it. Delete and re-push the affected image tags; purge caches; notify the security team and follow the incident process.
3. Root cause: the build copied an env file or used `ARG`/`ENV` with a secret; use BuildKit secrets (`--mount=type=secret`) and `.dockerignore`; add pre-commit and CI secret scanning (gitleaks, GitHub secret scanning with push protection).

CVE handling:
- Triage by severity, exploitability (is the vulnerable component loaded and reachable), and fix availability. Rebuild with a patched base image; update dependencies (Spring Boot BOM patch releases, `mvn versions` and dependency check/OWASP Dependency-Check or SCA tools).
- Prefer minimal images (distroless/JRE slim) that reduce attack surface.
- Define an SLA: critical in days, high in weeks; automate with Dependabot/Renovate and scheduled rebuilds; fail the pipeline on critical findings with an exception process requiring approval and an expiry.
- Generate an SBOM (CycloneDX) for each build and sign images (cosign) so deployments admit only signed, scanned images (admission policy).

Do not disable the scanner or lower the threshold to "make the build pass".

Measure: time to rotate, time to patch, count of open critical findings, secret-scan detections.
**Follow-ups:**
- Why does git history cleanup not remove exposure?
- How do you decide whether a CVE is exploitable?
- What is an SBOM used for?
**Pitfalls:**
- Deleting the commit and calling it fixed.
- Ignoring rotation.
- Suppressing the scanner.

### Q103. How do you manage configuration and secrets across dev, test, staging and prod for a Spring Boot service?
**Tags:** DevOps, Intermediate, Concept
**Analogy:** A hotel key system: the same door design everywhere, but each building gets its own keys, kept in a locked key cabinet, never printed on the door.
**Model answer:**
Separate config (non-sensitive, per environment) from secrets (credentials, keys, tokens).

- One immutable artifact promoted across environments; behavior differs only by external config (twelve-factor).
- Non-secret config: `application.yml` defaults; per-environment overrides via environment variables, Kubernetes ConfigMaps, CF environment, or a config server. Profiles choose adapters (`local`, `test`, prod-like), not secrets (CONTRACT section 9).
- Secrets: a secret manager (Vault, cloud secret manager, CredHub, Kubernetes Secrets with encryption at rest and RBAC, External Secrets Operator syncing from a vault). Inject as environment variables or mounted files (`spring.config.import=configtree:`). Prefer short-lived dynamic credentials (database credentials from Vault) over long-lived static ones.
- Never in git, images, logs, CI output or example files (the repository uses dummy values such as `local-dev-secret-not-for-production` and a `.env.example` template, CONTRACT section 9 and 11).
- Rotation: support dual keys (webhook secrets by key id), restart-free reload where feasible (`@RefreshScope` or rolling restart), documented rotation runbooks and expiry alerts.
- Least privilege: each environment and service has its own credentials; prod secrets inaccessible to developers by default; audit access.
- CI/CD: pipeline credentials stored in the CI secret store (Jenkins credentials, Bamboo variables marked secret, GitHub Actions secrets with environments and required reviewers), masked in logs; prefer OIDC federation to cloud over stored keys.
- Validation: fail fast at startup if a required secret is absent; startup config log with masked values; `management.endpoint.env.show-values=never`.
- Configuration drift: manage manifests as code (GitOps) with review.

Measure: secrets age, rotation compliance, secret-scan findings, config-related incidents.
**Follow-ups:**
- Kubernetes Secrets are only base64: what else do you do?
- How do you rotate a database password without downtime?
- How do you stop developers from reading prod secrets?
**Pitfalls:**
- Committing `.env` or `application-prod.yml` with credentials.
- Sharing one secret across environments.
- Logging configuration at startup unmasked.

### Q104. What stages would you put in a CI/CD pipeline for this platform, and what gates matter most?
**Tags:** CI/CD, Intermediate, Concept
**Analogy:** An airport: many checkpoints (ticket, security, boarding), each catching a different problem before the plane leaves.
**Model answer:**
Pipeline stages (the repository includes a Jenkinsfile, Bamboo specs and GitHub Actions workflows, CONTRACT section 11; they are statically validated here, not run):
1. Checkout and cache dependencies; build with Maven wrapper pinned to JDK 21.
2. Fast checks: compile, unit tests, static analysis (Checkstyle/SpotBugs/Sonar), formatting, secret scan (gitleaks), license and dependency vulnerability scan (OWASP Dependency-Check, SCA).
3. Integration tests (profile `test`, H2, in-memory bus); optional container tests (`-Pcontainers`, Testcontainers) on runners with Docker.
4. Frontend: `npm ci`, lint, unit tests (Karma/Jasmine headless), production build.
5. API contract check: springdoc output versus committed OpenAPI spec (CONTRACT section 1), breaking-change diff.
6. Package: build image, scan (Trivy), generate SBOM, sign, push with an immutable tag (commit sha).
7. Deploy to dev automatically; run smoke tests; promote to staging; run end-to-end/API tests (`tests/end-to-end`), performance smoke (k6 on a schedule or pre-release), security tests (ZAP baseline).
8. Production: manual approval (change management in financial services), progressive rollout (canary or blue-green), automatic verification (SLO-based checks on error rate and latency) and automatic rollback.
9. Post-deploy: tag release, notify, update change record, capture deployment metrics.

Most important gates: unit and integration tests, vulnerability and secret scanning, schema migration safety, smoke tests post-deploy, and a clear rollback path.

Principles: build once, promote the same artifact; keep the pipeline fast (parallelize, cache); trunk-based development with short-lived branches and required reviews; pipelines as code; least-privilege deployment credentials; reproducible builds.

Measure (DORA): deployment frequency, lead time for changes, change failure rate, mean time to restore; plus pipeline duration and flaky test rate.

<!-- in-project:b32 -->
> [!NOTE] In this project
> **File:** `Jenkinsfile`
>
> Stage names are taken from the real `Jenkinsfile`; the nesting is summarised. Credentials are referenced by id only. `bamboo-specs/bamboo.yaml` expresses the same flow for Bamboo. Neither pipeline was run.
>
> ```groovy
> // stages, in order (declarative pipeline; NOT executed: no Jenkins instance was available)
> stage('Version')
> stage('Build and test')            { parallel { 'Backend verify'; 'Frontend lint, test, build'; 'Static analysis' } }
> stage('Security and integration')  { parallel { 'OWASP dependency-check'; 'npm audit';
>                                                 'Integration tests (Testcontainers)'; 'Config validation' } }
> stage('Docker build and scan')     { parallel { 'Backend image'; 'Frontend image' } }
> stage('Deploy to test')            // manual gate: booleanParam DEPLOY_TEST
> stage('Post-deploy smoke')
> ```

**Follow-ups:**
- Where do performance tests belong?
- How do you protect production deployments?
- What would you do about a 45-minute pipeline?
**Pitfalls:**
- Rebuilding artifacts per environment.
- Optional security scans that everyone skips.
- No automated rollback.

### Q105. Compare Jenkins, Bamboo and GitHub Actions. How would you structure each pipeline for a Java and Angular repository?
**Tags:** CI/CD, Intermediate, Concept
**Analogy:** Three kinds of factory lines: a fully customizable one you maintain yourself (Jenkins), a tidy one from a vendor tied to its own toolset (Bamboo), and a hosted one that lives next to your code (Actions).
**Model answer:**
Jenkins: self-hosted, open-source, huge plugin ecosystem. Pipeline as code in a `Jenkinsfile` (declarative `pipeline { agent, stages, post }`), shared libraries for reuse, agents (static or Kubernetes pods). Strengths: flexibility, on-prem fit. Risks: plugin sprawl and security patching, controller as a single point of failure, snowflake configuration; mitigate with Configuration as Code, ephemeral agents, folder-level RBAC and credentials bindings.

Bamboo (Atlassian): integrates tightly with Jira and Bitbucket; plans, stages, jobs, tasks; Bamboo Specs (Java or YAML) keep plans as code; deployment projects with environments and release versions give a clear promotion model. Strengths: traceability (issue to build to deployment), simple for Atlassian shops. Weaknesses: smaller ecosystem, scaling remote agents costs, vendor direction (Atlassian is moving customers toward cloud offerings).

GitHub Actions: workflows in `.github/workflows/*.yml`, event-driven (push, pull request, schedule, `workflow_dispatch`), marketplace actions, hosted or self-hosted runners, environments with required reviewers and secrets, OIDC to clouds, matrix builds, reusable workflows. Strengths: developer experience and proximity to code review. Risks: third-party action supply chain (pin by SHA), runner minutes cost, secrets exposure to forks (`pull_request_target` caution).

Common structure for Java plus Angular: parallel jobs for `backend` (Maven verify with cache) and `frontend` (`npm ci`, test, build); then a package job producing the image; deploy jobs gated by environment approvals; the same stages in all three tools, differing only in syntax. Keep logic in scripts (`scripts/`) so it is tool-neutral and locally runnable; the tool only orchestrates.

All three: secrets from the CI secret store, least privilege, protected branches, required status checks.

Measure: pipeline duration, queue time, failure rates.
**Follow-ups:**
- How do you pin and review third-party actions or plugins?
- How would you migrate from Bamboo to Actions?
- Why keep logic in scripts?
**Pitfalls:**
- Business logic embedded in YAML.
- Unpinned third-party actions.
- Long-lived cloud keys in CI.

### Q106. A release caused elevated errors in production. What is your rollback approach, including database migrations?
**Tags:** CI/CD, Senior, Scenario
**Analogy:** An undo button that only works if you designed the previous version to keep working on the new document format.
**Model answer:**
Clarify: how fast must we restore (error budget burning), whether the release included a schema change or message format change, and whether data was written in a new shape.

Decision: roll back versus roll forward. If the previous version is known good and compatible, roll back immediately (restore service first, diagnose later). Do not debug in production while users suffer.

Mechanics by platform: Kubernetes `kubectl rollout undo` or redeploy previous image tag (immutable tags make this exact); CF re-map route to blue or `cf push` previous artifact; feature flags can disable the new behavior without redeploying.

Database safety (expand-and-contract): releases must be backward-compatible with the previous version for at least one release. Additive migrations (new nullable columns, new tables) allow rollback. Destructive changes (drop column) ship only after the old version is retired. Never rely on down-migrations for production; Flyway versioned scripts are forward-only; if data corruption occurred, restore or fix forward with a new migration and a reviewed repair script.

Messaging safety: envelope changes must be tolerant; consumers handle old and new formats; messages enqueued during the bad release may need replay or DLQ handling after the rollback. Idempotent consumers make replay safe.

Verification after rollback: SLO dashboards, smoke tests, queue depth and DLQ trends, reconcile messages touched during the incident window (stuck or unknown states).

Post-incident: blameless review, add a test that would have caught it, tighten canary thresholds, consider automated rollback on SLO breach, record a change failure for DORA.

Measure: time to detect, time to rollback, number of affected messages.
**Follow-ups:**
- When would you roll forward instead?
- How do feature flags help?
- What if the migration already dropped a column?
**Pitfalls:**
- No rollback plan.
- Destructive migrations bundled with code.
- Debugging live instead of restoring service.

### Q107. Compare rolling, blue-green and canary deployments for a messaging service with queue consumers.
**Tags:** CI/CD, Senior, Concept
**Analogy:** Replacing the crew of a moving ship one sailor at a time (rolling), building a second ship and switching (blue-green), or letting a small group try the new route first (canary).
**Model answer:**
- Rolling: replace instances gradually (`maxSurge`, `maxUnavailable: 0`). Low resource overhead; both versions run together during the rollout; rollback is another rolling update. Needs backward-compatible schema and message formats.
- Blue-green: a full second environment is validated, then traffic switches; instant rollback by switching back; double capacity cost during the switch; shared database still requires compatibility. Good when you need a clear atomic cutover.
- Canary: route a small percentage (1 to 5 percent) of traffic to the new version, compare metrics against the baseline, then increase. Best for risk reduction; needs traffic splitting (ingress, service mesh, Argo Rollouts or Flagger) and automated analysis.

Queue consumer nuances:
- Consumers pull from a shared queue, so a "traffic percentage" is by replica count: one canary replica of ten processes roughly a tenth of messages. Tag canary-processed messages in logs and metrics (`version` label) to compare outcome rates and latency.
- The new version might process a message that the old version later retries; state transitions must be compatible, and the state machine table versioning must be additive (CONTRACT section 3.1).
- Poison-message risk: a buggy canary can dead-letter messages; use a low replica count and alert on `messaging.deadletter.total`.
- Pollers and sweepers run in both versions: safe thanks to CAS claims.

API nuance: canary on API pods by weighted routing; idempotency keys protect retries that land on different versions.

Automation: promote only if error rate, p95 latency, DLQ growth and provider failure rates stay within the SLO thresholds during the observation window; otherwise abort and roll back automatically.

Measure: change failure rate, rollout duration, blast radius (messages processed by the bad version).
**Follow-ups:**
- How do you canary a consumer?
- What metric aborts a canary?
- Which strategy for a schema change?
**Pitfalls:**
- Canary without comparison metrics.
- Incompatible message formats across versions.
- Treating blue-green as a database rollback.

## Security

### Q108. How would you secure a messaging API used by multiple enterprise applications?
**Tags:** Security, Staff, Scenario
**Analogy:** A secure office building: ID badges at the door (authentication), key cards that open only your floor (authorization and tenant isolation), cameras and logs (audit), and a limit on how many people can crowd through the door (rate limiting).
**Model answer:**
Clarify: who the callers are (internal services, partner systems, humans), data sensitivity (customer contact data and message content), compliance needs (audit, retention), and threat model (credential theft, tenant data leakage, abuse, injection, replay).

Controls aligned to the platform (CONTRACT sections 1, 5 and 6):
- Authentication: OAuth2 client credentials for service clients; JWT access tokens (RS256) validated by Spring Security 6 resource server against the issuer's JWKS (`issuer-uri`), checking signature, `iss`, `aud`, `exp`, `nbf`. Humans via authorization code with PKCE and OIDC. Short token lifetimes, key rotation, no dev token endpoint outside `local/test`.
- Authorization: roles (`SENDER`, `TENANT_ADMIN`, `OPERATOR`, `AUDITOR`...) plus scopes (`messages:bulk`), enforced both at the endpoint and at the resource level (`AuthorizationService`). Least privilege per client application.
- Tenant isolation: the `tenant` claim scopes every query; cross-tenant access returns 404, not 403, to avoid leaking existence; `*` only for platform admin. Test it explicitly (security tests).
- Input and output: size limits, validation, allow-listed template variables, no expression evaluation, HTML escaping, masked addresses in responses, no rendered bodies returned.
- Abuse protection: per-client rate limits, bulk scope, idempotency required, backpressure.
- Transport: TLS 1.2+ everywhere, mTLS between internal services where required, HSTS at the edge.
- Webhooks: HMAC-SHA256 signatures, timestamp tolerance, replay cache (callbacks are `permitAll` in Spring Security but signature-validated, never trust the network).
- Secrets: external secret store, rotation, dual keys.
- Data protection: encryption at rest, data minimization (store template variables, not bodies; hash suppression addresses), retention jobs, redacted logs.
- Audit: append-only audit events with actor, role, tenant, correlation id.
- Operations: dependency and image scanning, OWASP API Top 10 testing, alerting on invalid signatures and authorization failures.

Measure: auth failure rate, cross-tenant attempt counts, rate-limit rejections, secret rotation age.

<!-- in-project:b19 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/config/SecurityConfig.java`
>
> Default-deny (`anyRequest().denyAll()`), stateless sessions, roles enforced with `@PreAuthorize` on controllers via `@EnableMethodSecurity`. CSRF is disabled only because there is no cookie session: bearer tokens are not sent automatically by browsers.
>
> ```java
> http
>     .csrf(AbstractHttpConfigurer::disable)
>     .formLogin(AbstractHttpConfigurer::disable)
>     .httpBasic(AbstractHttpConfigurer::disable)
>     .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
>     .authorizeHttpRequests(a -> {
>         a.requestMatchers("/api/v1/callbacks/**").permitAll();   // HMAC-verified in the service
>         a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
>         a.requestMatchers("/actuator/**").hasRole(Roles.PLATFORM_ADMIN);
>         a.requestMatchers("/api/**", "/dev/**").authenticated();
>         a.anyRequest().denyAll();
>     })
> ```

**Follow-ups:**
- How do you test tenant isolation?
- How do you revoke a compromised client?
- Why 404 instead of 403?
**Pitfalls:**
- API keys in query strings.
- Authentication only at the gateway with an open internal network.
- Roles checked in the UI only.

### Q109. Explain the main OAuth2 flows and which you would use for service-to-service, web UI and mobile.
**Tags:** Security, Intermediate, Concept
**Analogy:** Different ways to get a visitor badge: a company-issued badge for an employee (client credentials), reception checking your ID and you proving it with a secret handshake (authorization code with PKCE).
**Model answer:**
- Client credentials: a confidential client (service) authenticates with its own credentials (secret, private key JWT, or mTLS) and receives an access token representing the application, not a user. Use for service-to-service calls such as an enterprise application submitting messages (`SENDER` role). No refresh token.
- Authorization code with PKCE: for user login via browser or mobile app. The client redirects to the authorization server; user authenticates; the client exchanges the code (plus PKCE verifier) for tokens. PKCE protects against code interception, and is now the recommended flow for all public clients and even confidential ones. Use for the Angular console (SPA) and mobile apps.
- Refresh token: obtains new access tokens without re-login; rotate and bind refresh tokens, keep them out of browser storage where possible.
- Device authorization: for input-constrained devices.
- Deprecated or discouraged: implicit flow (tokens in URL fragments) and resource owner password credentials (apps handle user passwords).
- Token exchange / on-behalf-of: propagate user identity between services with downscoped tokens.
- OIDC adds an ID token and user info for authentication on top of OAuth2 authorization.

Platform specifics (CONTRACT section 1): the resource server validates JWTs; locally a dev token endpoint stands in for the authorization server (disabled outside `local/test`). The Angular UI uses role-aware routing, but guards are UX only; the server enforces authorization (CONTRACT section 10).

Security details: exact redirect URI matching, `state` and `nonce`, short-lived access tokens (5 to 15 minutes), audience restriction, scope minimization, secure token storage (in memory plus HttpOnly cookies via a backend-for-frontend pattern is stronger than localStorage).

Measure: token issuance failures, token lifetime, refresh usage.
**Follow-ups:**
- Why is PKCE needed for SPAs?
- What is the backend-for-frontend pattern?
- Why was the implicit flow deprecated?
**Pitfalls:**
- Using client credentials for user actions.
- Storing tokens in localStorage without understanding XSS risk.
- Long-lived access tokens.

### Q110. What are the common JWT validation mistakes, and what must a resource server verify?
**Tags:** Security, Senior, Concept
**Analogy:** A concert wristband: you check that it is genuine (signature), for this event (audience), not expired, and issued by the real organizer (issuer), rather than trusting what the wristband says about itself.
**Model answer:**
A JWT is `header.payload.signature`. The resource server must verify:
- Signature with a trusted key (RS256/ES256 via the issuer's JWKS), selecting the key by `kid`, with a fixed allow-list of algorithms. Reject `alg: none` and never let the token choose a weaker algorithm (algorithm confusion: RS256 public key used as an HMAC secret).
- `iss` equals the expected issuer; `aud` contains this API; `exp` and `nbf` valid with a small clock skew; optionally `iat`, `jti` for replay-sensitive use.
- Authorization data (`roles`, `scope`, `tenant`) come from the validated token only; map them explicitly to authorities (CONTRACT section 1 claims).
- Revocation: JWTs are stateless; use short lifetimes, deny-lists for emergency (by `jti`), or introspection for opaque tokens where immediate revocation matters.

Common mistakes:
- Decoding without verifying the signature, or accepting unsigned tokens.
- Not validating audience (a token for another API accepted).
- Trusting the `tenant` header or request body over the token claim.
- Hard-coding a shared HS256 secret across services.
- Fetching JWKS on every request (no caching) or never refreshing (breaks key rotation); Spring caches and refreshes the JWKS.
- Putting sensitive data in the payload (it is only encoded, not encrypted).
- Overlong lifetimes and no rotation of signing keys.
- Logging tokens.

Spring Security 6: `spring.security.oauth2.resourceserver.jwt.issuer-uri` configures decoding and issuer validation; add an audience validator; custom `JwtAuthenticationConverter` maps `roles`. Local profile uses `NimbusJwtDecoder.withPublicKey` with an in-memory key (CONTRACT section 1).

Tests: expired token, wrong issuer, wrong audience, tampered payload, `alg: none`, missing tenant claim; all must yield 401.

Measure: 401 rate by reason, JWKS refresh failures.

<!-- in-project:b17 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/config/JwtConfig.java`
>
> This decoder exists only when `messaging.security.dev-token-enabled=true` (profiles local/test; an in-memory RSA key, RS256). Production uses `spring.security.oauth2.resourceserver.jwt.issuer-uri`, where Spring fetches the JWKS and validates signature, `exp`/`nbf` and issuer. The algorithm is fixed by the key type, so `alg: none` and HS256 confusion are not possible.
>
> ```java
> NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(devRsaKey.toRSAPublicKey()).build();
> OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
>         new JwtTimestampValidator(java.time.Duration.ofSeconds(2)),
>         JwtValidators.createDefaultWithIssuer(props.security().devIssuer()));
> decoder.setJwtValidator(validator);
> return decoder;
> ```

**Follow-ups:**
- How do you handle key rotation?
- When would you use opaque tokens?
- What is algorithm confusion?
**Pitfalls:**
- Skipping audience checks.
- Trusting claims from request headers.
- Logging full tokens.

### Q111. During testing, one tenant can read another tenant's messages by changing an ID in the URL. How do you fix and prevent this class of bug?
**Tags:** Security, Senior, Scenario
**Analogy:** A hotel key card that opens any room if you just change the room number you type in.
**Model answer:**
This is Broken Object Level Authorization (BOLA/IDOR), the top item in the OWASP API Security Top 10. Clarify: which endpoints, whether list or detail, whether write operations are affected, and whether it is exploitable in production (check audit logs for cross-tenant access).

Fix: enforce tenant scoping server-side for every data access, derived from the validated token claim, never from client input:
- Repository queries always include the tenant: `findByIdAndTenantId(id, tenant)`; a missing row returns 404 (the platform deliberately returns 404, not 403, so existence is not leaked, CONTRACT section 5).
- Central enforcement: `TenantContext` populated from the JWT and `AuthorizationService` for resource-level checks; Hibernate filters or a repository base layer so developers cannot forget the tenant predicate. List endpoints scope automatically; `PLATFORM_ADMIN` uses an explicit `X-Tenant-Id` header or sees all by design.
- Child resources: check the parent: `/messages/{id}/history`, `/dead-letters/{messageId}/replay`, `/diagnostics/messages/{id}` must all verify tenant ownership, not just the first endpoint fixed.
- Cache keys and idempotency keys include the tenant; unique keys are per tenant (`UNIQUE(tenant_id, idempotency_key)`).
- Mongo diagnostics always filter by `tenantId`.
- Avoid guessable ids (UUIDs help but do not replace authorization).

Prevention:
- Automated tests: two tenants, call every endpoint with tenant A token on tenant B ids and assert 404 (the repository has security tests, `tests/security`, CONTRACT section 11).
- Code review checklist item; static analysis for repository methods lacking tenant.
- Defense in depth: database row-level security (PostgreSQL RLS) as an extra layer in high-risk setups.
- Monitoring: alert on 404 bursts per client (enumeration attempts).

Measure: number of endpoints covered by isolation tests, cross-tenant 404 counts.

<!-- in-project:b18 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/security/AuthorizationService.java`
>
> The tenant comes only from the verified JWT claim. `JwtAuthConverter` rejects tokens whose `tenant` claim is missing or invalid (401) and only lets `PLATFORM_ADMIN` carry the wildcard tenant `*`.
>
> ```java
> /**
>  * Tenant isolation: a resource of another tenant is reported as 404, never 403, so its existence is not leaked.
>  */
> public void assertTenantAccess(String ownerTenantId, String what) {
>     String scope = tenantContext.scopeTenant();
>     if (scope != null && !scope.equals(ownerTenantId)) {
>         throw new ApiException.NotFoundException(what);
>     }
> }
> ```

**Follow-ups:**
- Why not rely on UUIDs being unguessable?
- How would row-level security work with a connection pool?
- How do you handle admin cross-tenant access safely?
**Pitfalls:**
- Fixing one endpoint only.
- Returning 403 and confirming existence.
- Trusting a tenant id in the request body.

### Q112. Map the OWASP API Security Top 10 (2023) to risks and controls in a messaging API.
**Tags:** Security, Senior, Concept
**Analogy:** A building inspector's checklist: doors, locks, windows, alarms, each checked against known ways burglars get in.
**Model answer:**
- API1 Broken Object Level Authorization: tenant isolation on every object access, 404 for foreign objects, tests.
- API2 Broken Authentication: validated JWTs, short lifetimes, no dev endpoints in prod, strong client secret handling, rate limit on token endpoints.
- API3 Broken Object Property Level Authorization: do not return rendered bodies or full addresses (masked only, CONTRACT section 5); prevent mass assignment (request DTO records with explicit fields, not entities; clients cannot set `status`, `tenantId`, `attemptCount`).
- API4 Unrestricted Resource Consumption: rate limits, request size limit (256 KB), page size cap (200), bulk row caps, concurrency limits, timeouts, backpressure.
- API5 Broken Function Level Authorization: role checks per endpoint (`/ops/consumers/pause` for `PLATFORM_ADMIN` only, `requeue` for `OPERATOR`), four-eyes template approval.
- API6 Unrestricted Access to Sensitive Business Flows: bulk sends require `messages:bulk` scope; priority and category abuse (a client labeling marketing as `SECURITY_ALERT` to bypass quiet hours) prevented by authorization of category per client; consent rules enforced server-side.
- API7 Server-Side Request Forgery: provider URLs from configuration, not user input; webhooks outbound (if any) allow-listed; no fetching of user-supplied URLs.
- API8 Security Misconfiguration: hardened actuator exposure, CORS allow-list, security headers, no verbose errors (generic `INTERNAL_ERROR`), dev flags off by profile, secure defaults.
- API9 Improper Inventory Management: committed OpenAPI spec, versioned `/api/v1`, test that docs match routes, retire old versions; no forgotten debug endpoints (`/dev/*` only in `local/test`).
- API10 Unsafe Consumption of APIs: validate provider callbacks and responses (signature, schema, size), timeouts, treat provider data as untrusted, do not log raw payloads with PII.

Add logging and monitoring: audit events, alerts on invalid signatures and 401/403/404 spikes.

Measure: security test coverage per category, findings from ZAP/dependency scans, time to fix.
**Follow-ups:**
- Give an example of mass assignment in this API.
- How would you test API6?
- Where does CORS fit?
**Pitfalls:**
- Reciting the list without concrete controls.
- Treating authentication as the only issue.
- Forgetting business-flow abuse.

### Q113. How do you verify webhook signatures and prevent replay attacks?
**Tags:** Security, Senior, Concept
**Analogy:** A wax seal with the date stamped inside it: the seal proves who sent it and the date proves it is not an old letter replayed.
**Model answer:**
Webhook endpoints are public, so authenticate each request cryptographically. Design from CONTRACT section 1: header `X-Signature: t=<epoch>,v1=<hex>` where `v1 = HMAC-SHA256(secret, t + "." + rawBody)`, plus `X-Provider-Key-Id` (which secret) and `X-Event-Id` (unique event).

Verification steps (`SignatureVerifier`, `ReplayGuard`):
1. Read the raw request bytes before any JSON parsing or re-serialization (whitespace or key order changes break the signature).
2. Look up the secret by `X-Provider-Key-Id`; unknown key id yields rejection. Secrets come from configuration or a secret store, never code.
3. Parse `t` and `v1`; compute the expected HMAC over `t + "." + rawBody` and compare in constant time (`MessageDigest.isEqual`) to avoid timing leaks.
4. Check the timestamp is within tolerance (5 minutes) both directions, which limits replay windows; requires clocks in sync (NTP).
5. Replay cache: record `X-Event-Id` (table `replay_guard` or Redis with TTL at least the tolerance). A repeated id is a duplicate (return 2xx for legitimate provider retries but do not reapply) or an attack; the business-level dedupe `(provider_id, provider_event_id)` is a second layer.
6. Respond `401 INVALID_SIGNATURE` for every signature problem without detail (unknown provider, unbound key and bad MAC all look the same); a replayed event id is answered `200 {"result":"replayed"}`; increment `messaging.callbacks{result="invalid_signature"}`; rate-limit or alert on bursts.

Rotation: accept multiple active key ids during rotation. Limits: the HMAC proves origin and integrity only if the secret stays secret; IP allow-lists are an additional signal, not a replacement.

Alternatives: asymmetric signatures (provider signs with a private key; you verify with the public key) avoid sharing secrets; mTLS.

Testing: valid, tampered body, wrong secret, expired timestamp, future timestamp, replayed event id, missing headers.

Measure: invalid signature count, replay count, verification latency.

<!-- in-project:b07 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/callback/SignatureVerifier.java`
>
> Several `v1=` candidates are accepted so a provider can sign with two secrets during rotation. The MAC is computed over `t + "." + rawBody` on the raw bytes. At the HTTP layer every failure result maps to the same `401 INVALID_SIGNATURE`.
>
> ```java
> long skew = Math.abs(clock.instant().getEpochSecond() - t);
> if (skew > props.callback().tolerance().toSeconds()) {
>     return Result.TIMESTAMP_OUT_OF_TOLERANCE;
> }
> byte[] expected = sign(secret, t, rawBody).getBytes(StandardCharsets.US_ASCII);
> boolean match = false;
> for (String c : candidates) {
>     // bitwise OR keeps the loop free of early exits; isEqual is constant-time for equal lengths
>     match |= MessageDigest.isEqual(expected, c.getBytes(StandardCharsets.US_ASCII));
> }
> return match ? Result.VALID : Result.MISMATCH;
> ```

**Follow-ups:**
- Why include the timestamp in the signed string?
- What if the provider cannot send a timestamp?
- Why return 2xx for duplicates?
**Pitfalls:**
- Comparing signatures with `equals`.
- Verifying after parsing and re-serializing JSON.
- No replay protection.

### Q114. You suspect a provider's webhook signing secret has leaked. What do you do?
**Tags:** Security, Senior, Scenario
**Analogy:** Someone may have copied the factory's official stamp, so you issue a new stamp, start rejecting the old one, and review everything stamped recently.
**Model answer:**
Clarify: evidence of leak (public repository, log, ex-employee), exposure window, and whether forged callbacks were accepted (they could mark messages delivered or failed, or suppress recipients).

Containment and rotation:
1. Generate a new secret and register a new key id (`X-Provider-Key-Id`), configure it in the secret store; the verifier supports multiple key ids (CONTRACT section 1).
2. Coordinate with the provider to start signing with the new key; during a short overlap accept both; then disable the compromised key id immediately after the provider switches (or at once if the risk is high, accepting a delivery-status gap that reconciliation will repair).
3. Tighten temporarily: IP allow-list for the provider's ranges, reduce timestamp tolerance, alert on all invalid signatures.

Investigation:
- Query `delivery_event` and audit logs for events received during the exposure window; compare source IPs, user agents, event id patterns, and rate anomalies.
- Reconcile suspicious messages against provider records via `lookup`/reports (`ReconciliationService`): anything whose terminal status came from an unverifiable event gets corrected, with audit events recording corrections.
- Check for suppressions added by forged bounces or complaints; review and remove illegitimate ones.

Root cause and prevention: find how it leaked (scan history, rotate other secrets from the same source), add secret scanning, separate secrets per provider and environment, shorten rotation intervals, document the runbook, and consider asymmetric signatures.

Incident handling: notify security and compliance as required, record timeline, post-incident review.

Measure: time to rotate, number of suspicious events found and corrected, residual mismatches after reconciliation.
**Follow-ups:**
- How do you rotate without losing callbacks?
- What if the provider cannot rotate quickly?
- Why can forged events be harmful here?
**Pitfalls:**
- Rotating without investigating prior events.
- Leaving the old key valid indefinitely.
- Committing the new secret to the repository.

### Q115. Logs and diagnostics must be useful to operators but must not leak customer data. How do you apply data minimization and masking?
**Tags:** Security, Senior, Scenario
**Analogy:** Receipts that show only the last four digits of a card: useful for matching a purchase, useless for stealing.
**Model answer:**
Clarify: what personal data the platform touches (names, emails, phone numbers, device tokens, message content including financial details), applicable regulations (privacy laws, financial-services rules), and who needs which level of detail.

Principles (CONTRACT sections 2, 4 and 5):
- Collect and store less: persist template variables rather than rendered bodies (`payload_json` stores variables, with redaction policy), envelopes carry ids only (no content or PII), suppression stores `address_hash`.
- Mask at the edges: API responses return masked addresses only (`r***@example.test`, `+1555***0101`), never rendered bodies or full addresses (CONTRACT section 5).
- Redaction utility (`common/Redaction`): central functions to mask emails, phone numbers, tokens and known sensitive variable names; used in log statements, error messages, audit `detail_json`, diagnostics payloads (Mongo) before storage.
- Logging rules: log ids (message id, correlation id, tenant), not content; no tokens or secrets; structured logs (JSON) with an allow-list of fields; avoid logging request or response bodies; log level discipline; tests that assert known PII patterns do not appear in logs (a log-capture test).
- Access control: operator views show masked data; unmasking requires a separate role and is audited.
- Retention: retention jobs delete messages, events and diagnostics by policy (`messaging.retention.*`); Mongo TTL indexes; backups follow the same policy.
- Encryption: TLS in transit; encryption at rest for databases, backups and heap dumps; field-level encryption for particularly sensitive fields if required.
- Data subject requests: ability to find and delete or anonymize a recipient's data (`recipient.deleted`, CONTRACT section 3.3) while preserving suppression integrity (store hash only).
- Observability: avoid personal identifiers as metric labels (also a cardinality problem).

Measure: PII scanner findings in logs (automated), unmasking audit counts, retention job success.

<!-- in-project:b27 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/common/Redaction.java`
>
> Used before raw provider payloads reach Mongo diagnostics. Complementary rule (DEVIATIONS stage 2, item 8): free-text provider reasons are never copied into audit or log lines, only code-like reasons.
>
> ```java
> private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
> private static final Pattern LONG_DIGITS = Pattern.compile("\\+?\\d[\\d\\s-]{8,}\\d");
> private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
> public static final String MASK = "***";
> ```

**Follow-ups:**
- How do you debug an incident without message content?
- How do you test that logs are clean?
- How do you handle a deletion request versus audit retention?
**Pitfalls:**
- Logging request bodies "temporarily".
- Using email addresses as metric labels or ids.
- Unmasked data in heap dumps and backups.

### Q116. A template author puts HTML or a script into a variable value, and another template uses a variable in a link. How do you prevent XSS and injection in rendered emails?
**Tags:** Security, Senior, Scenario
**Analogy:** A mail-merge machine that must treat every name as plain text, even if the name is written as an instruction to the machine.
**Model answer:**
Clarify: who controls template text (authors with approval workflow) versus variable values (callers, customer data) and which channels render HTML (email) versus plain text (SMS, push).

Threats: HTML or script injection into email bodies (rendered in webmail), link manipulation (open redirect, `javascript:` URLs), template engine expression injection (server-side template injection) leading to code execution, header injection via CRLF in subjects or addresses.

Controls (CONTRACT section 2 `template/`):
- A safe, allow-listed `{{var}}` engine with no expression evaluation, no method calls, no includes: only substitution of declared variables (`VariableValidator` checks that supplied variables match the template's declared set and types). This eliminates SSTI.
- Context-aware escaping: HTML-escape values in HTML bodies (`<`, `>`, `&`, quotes); URL-encode values placed inside URLs; for URLs, validate scheme allow-list (`https` only) and optionally host allow-lists; plain-text channels do not need HTML escaping but need control-character stripping.
- Reject CR/LF in subject lines and header values to block header injection.
- Template governance: four-eyes approval (`approve` by a different person than author), versioning, published versions immutable, preview endpoint (`POST /templates/preview`) to review rendering with sample data.
- Limits: max variable lengths, max rendered size, segment count for SMS.
- Email client risk: do not embed active content; use inline images responsibly; include only trusted tracking domains.
- Tests: injection payload corpus (`<script>`, `"><img onerror>`, `{{7*7}}`, `${...}`, CRLF) must render inert text.

Failure behavior: rendering errors map to `TEMPLATE_RENDER_ERROR`; missing variables to `TEMPLATE_VARIABLE_MISSING` (CONTRACT section 5); never fall back to unescaped output.

Measure: rendering errors by code, injection-test pass rate.

<!-- in-project:b28 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/template/TemplateRenderer.java`
>
> For EMAIL the variable values (and defaults) are HTML-escaped; only variables declared by the template are accepted; subject line breaks are stripped to block header injection. Escaping covers HTML text context; URL-valued variables still need allow-listing of schemes.
>
> ```java
> public static String escapeHtml(String s) {
>     StringBuilder sb = new StringBuilder(s.length() + 16);
>     for (int i = 0; i < s.length(); i++) {
>         char c = s.charAt(i);
>         switch (c) {
>             case '&' -> sb.append("&amp;");
>             case '<' -> sb.append("&lt;");
>             case '>' -> sb.append("&gt;");
>             case '"' -> sb.append("&quot;");
>             case '\'' -> sb.append("&#39;");
>             case '`' -> sb.append("&#96;");
>             default -> sb.append(c);
>         }
>     }
>     return sb.toString();
> }
> ```

**Follow-ups:**
- Why is expression evaluation dangerous in templates?
- How would you handle rich text from customers?
- What about URL parameters inside templates?
**Pitfalls:**
- Escaping once for all contexts.
- Allowing a full template engine with expressions.
- Trusting approved templates to make variables safe.

### Q117. Compare mTLS and OAuth2 client credentials for authenticating internal service clients.
**Tags:** Security, Senior, Concept
**Analogy:** mTLS is both parties showing photo ID at the door; client credentials is showing a short-lived visitor pass issued by the front desk after proving who you are.
**Model answer:**
mTLS (mutual TLS): both client and server present X.509 certificates during the TLS handshake. Authenticates the connection at the transport layer, strong cryptographic identity, no secret sent over the wire. Operational cost: certificate issuance, rotation, revocation, trust store management (often automated by a service mesh or SPIFFE/SPIRE, or a private CA with cert-manager). Identity is the certificate subject; fine-grained authorization (scopes, roles, tenant) must still be mapped separately. Termination at gateways complicates end-to-end identity.

OAuth2 client credentials: the client authenticates to the authorization server (client secret, private-key JWT, or mTLS-bound) and gets a short-lived JWT carrying claims: roles, tenant, scopes (the platform's claims, CONTRACT section 1). The API validates the JWT; works through proxies and gateways; centralized policy and revocation by disabling the client, short expiry limits exposure. Risks: secret leakage (use private key JWT or mTLS client authentication to avoid shared secrets), token theft (use short lifetime, audience restriction, and sender-constrained tokens with DPoP or mTLS-bound tokens).

Combine them: mTLS for channel authentication and zero-trust networking between services (mesh) plus OAuth2 tokens for application-level authorization and tenant/role claims. For high-assurance partners in financial services, mTLS-bound access tokens (RFC 8705) give both.

Recommendation for this platform: JWT-based client credentials for authorization (multiple enterprise applications, tenant claims), TLS everywhere, optional mTLS for partner or service-mesh environments. Webhooks from providers use HMAC signatures (or asymmetric) because providers rarely support OAuth for outbound callbacks.

Measure: certificate expiry alerts, token issuance rate, authentication failures.
**Follow-ups:**
- What is a sender-constrained token?
- How do you rotate certificates without downtime?
- Why still validate tokens inside the mesh?
**Pitfalls:**
- Treating network location as identity.
- Long-lived client secrets shared by teams.
- mTLS without authorization.

### Q118. A security review finds the dev token endpoint reachable in a staging environment that mirrors production. How do you assess and fix it?
**Tags:** Security, Staff, Scenario
**Analogy:** A master key stuck in the door of a bank vault "just for testing": the first thing is to remove it, the second is to find out who used it.
**Model answer:**
Clarify: is it reachable from outside or only internal, what profile enabled it, can it mint tokens for `PLATFORM_ADMIN` with `tenant=*`, and does staging hold real data or share keys with production?

Severity: the endpoint mints RS256 JWTs from an in-memory key and the app validates with the matching public key (CONTRACT section 1). If staging uses the same validation path and the endpoint is reachable, anyone can become an admin of staging. If the signing key or the issuer configuration is shared with production, it is critical, because it could mint production-valid tokens. Confirm that staging and production use different issuers and keys.

Immediate actions:
1. Disable it: `messaging.security.dev-token-enabled=false` and ensure the controller bean is not created outside `local/test` profiles (conditional bean, not just a flag), then redeploy; block the path at the gateway as defense in depth.
2. Review access logs for calls to `/dev/token` and for tokens with admin claims; check audit events for unusual admin actions.
3. Rotate any key material that could be shared; invalidate sessions.

Prevention (secure defaults):
- Profile and conditional wiring: dev endpoints exist only with `local` or `test` profile; the prod-like profile uses `issuer-uri` and no dev endpoints (CONTRACT section 9).
- Startup guard: fail the application if a dev profile is active together with a production indicator (non-H2 DB, real issuer).
- Tests: a security test asserting `/dev/token` and `/dev/simulator/**` return 404 under the prod-like profile.
- CI check scanning route lists (OpenAPI/actuator mappings) for `/dev` in non-dev builds.
- Staging parity: staging should mirror production's security posture, so findings there are real findings.

Do not "make it work" by leaving the endpoint on for testers; provide proper test clients in the identity provider instead.

Measure: dev endpoint exposure checks in CI, number of misconfigurations found per release.
**Follow-ups:**
- Why use conditional beans rather than a runtime flag only?
- How would you provide test identities safely?
- What other dev-only features are risky (fault injection)?
**Pitfalls:**
- Only toggling a property.
- Shared keys across environments.
- Not reviewing logs for prior misuse.
