#!/usr/bin/env bash
#
# Deploy the platform to whatever cluster kubectl is pointed at.
#
#   ./k8s/deploy.sh                 build nothing, load nothing, just apply
#   ./k8s/deploy.sh --load          also push the local images into the node (kind or k3s)
#
# Idempotent, in the sense D28 means it: safe to re-run, checks before it creates, and re-running after
# a config change is the supported way to roll that change out.
#
# The three things this script does that a bare `kubectl apply -f k8s/` cannot:
#
#   1. builds the config-repo ConfigMap out of the real config-repo directory, so there is exactly one
#      copy of those files and it is the one the eight-terminal workflow and compose also read;
#   2. creates the Secret from the environment rather than from a committed file;
#   3. stamps a checksum of the config repo onto the config-server pod template, so that changing a
#      YAML file actually restarts the pod that serves it — the bug 10b hit twice.

set -euo pipefail

cd "$(dirname "$0")/.."
K8S=k8s
CONFIG_REPO=bookstore-platform/config-repo
NS=bookstore

SERVICES=(config-server api-gateway user-service book-service order-service
          payment-service notification-service analytics-service)

# ---------------------------------------------------------------------------------------------------
# WHICH IMAGES TO DEPLOY. Two modes, and the manifests hold neither of them as a literal decision:
#
#   (default)                     bookstore/<svc>:latest   +  imagePullPolicy: Never
#                                 images loaded into the node by --load. No registry involved.
#
#   IMAGE_REPO=ghcr.io/owner      <repo>/bookstore/<svc>:$IMAGE_TAG  +  imagePullPolicy: IfNotPresent
#   IMAGE_TAG=<git sha>           what Step 11's pipeline publishes, and the only form that can be
#                                 rolled back to, because a SHA tag names one build forever.
#
# A sed rather than a templating language. The manifests stay readable as themselves — `kubectl apply
# -f k8s/` works with no preprocessing at all, and that property is worth more here than the generality
# Helm would add.
# ---------------------------------------------------------------------------------------------------
IMAGE_REPO="${IMAGE_REPO:-}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
if [[ -n "$IMAGE_REPO" ]]; then
  IMAGE_REWRITE="s|image: bookstore/\([a-z-]*\):latest|image: $IMAGE_REPO/bookstore/\1:$IMAGE_TAG|; \
                 s|imagePullPolicy: Never|imagePullPolicy: IfNotPresent|"
  echo "==> images: $IMAGE_REPO/bookstore/<service>:$IMAGE_TAG"
else
  IMAGE_REWRITE="s|^|&|"     # a no-op, so the pipeline below has one shape rather than two
  echo "==> images: bookstore/<service>:latest, loaded into the node (no registry)"
fi

# ---------------------------------------------------------------------------------------------------
# 1. Images into the node.
#
# There is no registry behind `bookstore/...`. A kind node is a container with its own containerd, and
# a k3s node has its own too, so an image sitting in the laptop's Docker daemon is invisible to both.
# `imagePullPolicy: Never` in the manifests is what stops the kubelet trying to pull it from Docker Hub
# and failing with ImagePullBackOff on an image that is right there.
#
# Step 11 replaces all of this with a registry and a commit-SHA tag, which is also what makes rollback
# possible — `:latest` is the one tag you cannot roll back to.
# ---------------------------------------------------------------------------------------------------
if [[ "${1:-}" == "--load" ]]; then
  if kubectl config current-context 2>/dev/null | grep -q '^kind-'; then
    echo "==> loading images into the kind node"
    for svc in "${SERVICES[@]}"; do
      printf '    %-22s' "$svc"
      kind load docker-image "bookstore/$svc:latest" --name bookstore >/dev/null 2>&1
      echo "loaded"
    done
  else
    echo "==> importing images into k3s containerd"
    for svc in "${SERVICES[@]}"; do
      printf '    %-22s' "$svc"
      docker save "bookstore/$svc:latest" | sudo k3s ctr images import - >/dev/null
      echo "imported"
    done
  fi
fi

# ---------------------------------------------------------------------------------------------------
# 2. Namespace, then configuration, then workloads.
# ---------------------------------------------------------------------------------------------------
kubectl apply -f "$K8S/00-namespace.yaml"

echo "==> config-repo -> ConfigMap (from $CONFIG_REPO)"
# `create --dry-run=client | apply` rather than `kubectl create configmap`: create fails if it exists
# and apply cannot read a directory. This is the standard way to make a generated object declarative.
kubectl create configmap bookstore-config-repo \
  --from-file="$CONFIG_REPO" \
  --namespace "$NS" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> secrets"
# ENCRYPT_KEY defaults to the dev key that is already committed (see Step 6d for why that exception
# exists and why it is not a recommendation). AWS credentials are read from the environment, or from
# the AWS CLI's own config if it is installed — never from a file in this repository.
: "${ENCRYPT_KEY:=dev-only-config-server-master-key-do-not-reuse}"
AWS_KEY="${AWS_ACCESS_KEY_ID:-$(aws configure get aws_access_key_id 2>/dev/null || true)}"
AWS_SECRET="${AWS_SECRET_ACCESS_KEY:-$(aws configure get aws_secret_access_key 2>/dev/null || true)}"

# KEEP WHAT IS ALREADY THERE RATHER THAN OVERWRITING IT WITH NOTHING.
#
# `kubectl create secret ... | kubectl apply` replaces the whole object, so running this script from a
# machine with no AWS configuration would wipe credentials somebody had set by hand — and the symptom
# would appear later, in book-service, as cover and history endpoints that used to work.
#
# That machine is the CI runner. It has kubectl and no ~/.aws, by design: this pipeline deliberately
# holds no AWS credential (D38's argument). Without these four lines, every automated deploy would
# quietly break the two AWS-backed endpoints.
if [[ -z "$AWS_KEY" ]]; then
  AWS_KEY="$(kubectl get secret bookstore-secrets -n "$NS" \
    -o jsonpath='{.data.AWS_ACCESS_KEY_ID}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  AWS_SECRET="$(kubectl get secret bookstore-secrets -n "$NS" \
    -o jsonpath='{.data.AWS_SECRET_ACCESS_KEY}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  [[ -n "$AWS_KEY" ]] && echo "    reusing the AWS credentials already in the cluster"
fi

if [[ -z "$AWS_KEY" ]]; then
  echo "    no AWS credentials found — book-service will serve the catalog and fail on"
  echo "    /api/books/{id}/cover and /api/books/me/history. Everything else is unaffected."
fi

kubectl create secret generic bookstore-secrets \
  --from-literal=ENCRYPT_KEY="$ENCRYPT_KEY" \
  --from-literal=AWS_ACCESS_KEY_ID="$AWS_KEY" \
  --from-literal=AWS_SECRET_ACCESS_KEY="$AWS_SECRET" \
  --namespace "$NS" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f "$K8S/30-config.yaml"
kubectl apply -f "$K8S/10-postgres.yaml" -f "$K8S/20-kafka.yaml"

# ---------------------------------------------------------------------------------------------------
# 3. The config server, with the checksum that makes a config change roll out.
#
# A ConfigMap mounted as a volume is updated in place by the kubelet, but the process that read those
# files at startup never re-reads them — 10b's lesson, twice. Changing an annotation on the pod
# template changes the template, and a changed template is what a Deployment rolls out. The value is
# arbitrary; only "it differs when the files differ" matters.
# ---------------------------------------------------------------------------------------------------
CHECKSUM=$(cat "$CONFIG_REPO"/*.yml | sha256sum | cut -c1-16)
echo "==> config-repo checksum $CHECKSUM"

# EVERY workload, not only config-server. Stamping it on the server alone was a real gap, found in 10d
# by changing a gateway value: the config server rolled, and the gateway went on serving the old one.
# The server is not the only process that reads this configuration once at startup and never again.
#
# THE CONFIG SERVER GOES FIRST, AND THE SCRIPT WAITS FOR IT. Found in 11c, and it is a race rather than
# an ordering preference. Applying all eight at once rolls the config server and its seven clients
# simultaneously — so a client can start, fetch from the *outgoing* config-server pod, and come up
# holding the previous configuration, permanently, while every pod reports Ready and carries the new
# checksum annotation. The symptom was five services 404ing on an endpoint their configuration plainly
# enabled, and the fix for each was a restart that changed nothing else.
#
# It is the same shape as the 10b bug one level up: the thing that serves configuration is itself a
# process that must be current before anything reads it.
sed "s|replaced-by-deploy.sh|$CHECKSUM|" "$K8S/31-config-server.yaml" | sed "$IMAGE_REWRITE" | kubectl apply -f -
kubectl rollout status deployment/config-server -n "$NS" --timeout=300s

for f in 40-user-service 41-book-service 42-order-service \
         43-payment-service 44-notification-service 45-analytics-service 50-api-gateway; do
  sed "s|replaced-by-deploy.sh|$CHECKSUM|" "$K8S/$f.yaml" | sed "$IMAGE_REWRITE"
  echo "---"
done | kubectl apply -f -

kubectl apply -f "$K8S/60-autoscaling.yaml" -f "$K8S/70-monitoring.yaml"

echo
echo "==> waiting for the platform"
# `kubectl wait` on a condition rather than a sleep, for the same reason compose used
# `condition: service_healthy`. Expect CrashLoopBackOff on the way there: nothing orders these, so
# every service starts before the config server answers, fails fast, and is restarted with backoff
# until it succeeds. That is the ordering mechanism, not a symptom.
kubectl wait --for=condition=ready pod --all --namespace "$NS" --timeout=600s || {
  echo "not everything came up:"
  kubectl get pods -n "$NS"
  exit 1
}

echo
kubectl get pods -n "$NS"
echo
echo "the platform:  http://localhost:30080/api/books"
echo "Prometheus:    http://localhost:30090"
echo "Grafana:       http://localhost:30300"
