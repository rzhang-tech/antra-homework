# k8s — the platform on Kubernetes (Step 10c)

The same thirteen containers compose runs, as Deployments, StatefulSets, Services, a ConfigMap, a
Secret and three probes each. The manifests are plain YAML with no templating: what is applied is what
is in the file, which is the property that makes them readable in a review.

## Run it locally

```bash
kind create cluster --config k8s/kind-cluster.yaml
```

```bash
./scripts/build-images.sh
```

```bash
./k8s/deploy.sh --load
```

Then:

| | |
|---|---|
| the platform | **http://localhost:30080** — `/api/books`, `/api/auth/login`, `/api/orders`, `/api/payments` |
| Prometheus | **http://localhost:30090** — try `sum by (application) (rate(http_server_requests_seconds_count[5m]))` |
| Grafana | **http://localhost:30300** — anonymous, Prometheus datasource already provisioned |

No service's own port is reachable from outside, by construction rather than by rule.

The HPA needs metrics-server, which **k3s bundles and kind does not**. On kind, once:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

```bash
kubectl patch deployment metrics-server -n kube-system --type=json -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

```bash
kubectl get pods -n bookstore
```

```bash
kind delete cluster --name bookstore
```

`--load` pushes the local images into the node and is only needed when they have changed. There is no
registry behind `bookstore/...`, which is why every manifest sets `imagePullPolicy: Never` — Step 11
replaces both with a registry and a commit-SHA tag.

## Run it on the EC2 box

k3s instead of kind, and nothing else changes:

```bash
curl -sfL https://get.k3s.io | sh -s - --disable traefik --write-kubeconfig-mode 644
```

```bash
./k8s/deploy.sh --load
```

`--disable traefik` because api-gateway already *is* this platform's edge (Step 8); a second ingress in
front of it would be a second place to get routing, CORS and authentication wrong. `deploy.sh` detects
that the context is not kind and imports the images through `k3s ctr` instead.

Do not run compose and k3s at the same time on 8 GiB. `docker compose down` first.

## What is in each file

| | |
|---|---|
| `kind-cluster.yaml` | one node, port 30080 forwarded. Single-node on purpose: the target is one server |
| `00-namespace.yaml` | `bookstore`, which is the unit quota/NetworkPolicy/RBAC attach to — and a complete teardown |
| `10-postgres.yaml` | four StatefulSets, four headless Services, four PVCs |
| `20-kafka.yaml` | one broker; the probe split that keeps a JVM fork off the steady-state path |
| `30-config.yaml` | the ConfigMap holding every address that varies between deployments |
| `31-config-server.yaml` | config-repo as a mounted ConfigMap, `ENCRYPT_KEY` from a Secret |
| `40-user-service.yaml` | **the canonical service manifest** — probes, resources and ordering are explained here |
| `41`–`45` | book, order, payment, notification, analytics |
| `50-api-gateway.yaml` | the only NodePort, and the only Service that does not name its management port |
| `60-autoscaling.yaml` | HPAs on the four services that can scale, and why the other four cannot |
| `70-monitoring.yaml` | Prometheus, Grafana, and the seven alert rules — **the alert rules are the part worth reading** |
| `deploy.sh` | builds the ConfigMap and Secret, stamps the config checksum, applies, waits |

`deploy.sh` rolls the config server **first and waits for it**, then everything else. Applying all
eight at once lets a client fetch from the outgoing config-server pod and come up on the previous
configuration — permanently, while reporting Ready with the correct checksum. Found in 11c.

Deploying images from the registry rather than from the node:

```bash
IMAGE_REPO=ghcr.io/rzhang-tech IMAGE_TAG=$(git rev-parse HEAD) ./k8s/deploy.sh
```

## Three things worth reading `40-user-service.yaml` for

- **Why the liveness probe must not be `/actuator/health`.** With the database scaled to zero that
  endpoint does not return DOWN — it *hangs*, so a liveness probe against it kills a process that has
  nothing wrong with it, on every replica, for a problem no restart can fix.
- **Why readiness does not check the database either.** A readiness probe over a *shared* dependency
  empties every endpoint at once and turns one backend's outage into a total one.
- **Why there are no CPU limits, and why the CPU requests came down.** The first version requested
  2050m across the node — 12% of a laptop and over 100% of the t3.large this is going to.

## Teardown

```bash
kubectl delete namespace bookstore
```

Which deletes everything including the PersistentVolumeClaims, and therefore the data. That it is a
complete teardown is the test D28 applies to the AWS scripts: if something survives, something was
created that nobody wrote down.
