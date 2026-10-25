# Kubernetes Guide

**Execution status: NOT executed.** No kubectl, kustomize, helm or cluster was available. The manifests were only parsed as
YAML and checked with in-repo heuristics (`bash scripts/validate-infra.sh`: deployment has probes/non-root/read-only FS/preStop/spread; Helm templates have balanced
`{{ }}` and `end` counts). `kubectl kustomize`, `kubeconform`, `helm lint` and `helm template` were SKIPPED. The Helm chart has never been rendered.

## Layout
```
infrastructure/kubernetes/base/            Deployment, Service, HPA, PDB, Ingress, ConfigMap, Secret template, NetworkPolicy, ServiceAccount, ServiceMonitor
infrastructure/kubernetes/overlays/dev/    1 replica + StatefulSets for postgres/rabbitmq/redis/mongo (dummy creds, dev only)
infrastructure/kubernetes/overlays/prod-like/  3 replicas, bigger resources, DoNotSchedule zone spread, PDB minAvailable 2, dummy Secret removed
infrastructure/helm/messaging-platform/    chart (values.yaml, values-prod-like.yaml)
```

## Design choices
- **Probes.** `startupProbe` and `livenessProbe` use `/actuator/health/liveness`; `readinessProbe` uses `/actuator/health/readiness`. Startup allows up to 3 minutes (36 x 5s) for Flyway/JPA before liveness takes over.
- **Graceful shutdown.** `preStop: sleep 10` lets the endpoint/ingress drop the pod before SIGTERM; Spring graceful shutdown then drains in-flight work (CONTRACT: 30s). `terminationGracePeriodSeconds: 60` exceeds sleep + drain. Unacked RabbitMQ messages return to the queue.
- **Security context.** non-root uid 10001, `readOnlyRootFilesystem`, all capabilities dropped, seccomp RuntimeDefault, no service account token mounted, `/tmp` as an `emptyDir`.
- **Topology spread.** zone and hostname spread with `ScheduleAnyway` (dev) or `DoNotSchedule` for zones in prod-like.
- **HPA.** CPU 70% plus an **example** External metric `messaging_queue_depth` (target average 2000). It requires an external-metrics provider (prometheus-adapter or KEDA) mapping the Prometheus gauge; without one, remove that metric block (the dev overlay removes it; Helm enables it only with `autoscaling.queueDepth.enabled`). Consumer capacity also depends on DB pool and RabbitMQ prefetch, so scaling pods on queue depth is not free.
- **PDB.** `minAvailable` 1 (base), 2 (prod-like), 0 in dev so single-replica drains do not block.
- **NetworkPolicy.** default-deny plus an allow policy: ingress only from `ingress-nginx` and `monitoring` namespaces on 18080; egress to DNS, dependency pods, and the OTLP collector. Adjust namespace labels to your cluster; the Helm egress block is port-based and should be narrowed.
- **Secrets.** `secret.template.yaml` and chart `secrets.values` contain dummy placeholders only. Real clusters should use `secrets.existingSecret` (External Secrets / Vault / Sealed Secrets). The prod-like overlay deletes the dummy Secret; you must supply `backend-secrets` yourself.
- **Frontend.** Only the backend is deployed by these manifests. The frontend image (`infrastructure/docker/Dockerfile.frontend`) proxies `/api` to host `backend:18080` (compose naming); in Kubernetes the Ingress routes `/api` directly to the backend Service instead, and serving the SPA needs an extra Deployment or CDN (not included).
- **Dependencies.** The dev StatefulSets are examples (single replica, small PVC); RabbitMQ topology from `infrastructure/rabbitmq/definitions.json` must be imported separately. Use operators or managed services in real environments. PostgreSQL role setup is in `infrastructure/postgres/init.sql`.

## Usage (commands NOT executed)
```bash
kubectl kustomize infrastructure/kubernetes/overlays/dev | kubeconform -strict -ignore-missing-schemas -summary
kubectl apply -k infrastructure/kubernetes/overlays/dev
kubectl -n messaging-dev rollout status deploy/backend
kubectl -n messaging-dev port-forward svc/backend 18080:18080

helm lint infrastructure/helm/messaging-platform -f infrastructure/helm/messaging-platform/values-prod-like.yaml
helm upgrade --install msg infrastructure/helm/messaging-platform -n messaging-prod-like --create-namespace \
  -f infrastructure/helm/messaging-platform/values-prod-like.yaml --set image.tag=0.1.57-3fa9c21
```
The `messaging-dev` namespace in the overlay must exist (`kubectl create namespace messaging-dev`); Kustomize does not create it.

## Troubleshooting
| Symptom | Check |
|---|---|
| Pod `CrashLoopBackOff` | `kubectl logs`, DB/Rabbit reachability, `backend-secrets` keys present, Flyway privileges |
| Never Ready | readiness group includes dependencies; `kubectl exec`-less check via `port-forward` + `/actuator/health` |
| Rollout stuck | maxUnavailable 0: new pod failing readiness; check events |
| HPA `unknown` external metric | no external-metrics API; remove or install adapter/KEDA |
| Ingress 404 | host `messaging.example.test` and class `nginx` are placeholders; TLS secret `messaging-tls` must exist |
| Egress blocked | NetworkPolicy labels (`app.kubernetes.io/component: dependency`) and namespace names |
