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
sed "s|replaced-by-deploy.sh|$CHECKSUM|" "$K8S/31-config-server.yaml" | kubectl apply -f -

kubectl apply -f "$K8S/40-user-service.yaml" \
               -f "$K8S/41-book-service.yaml" \
               -f "$K8S/42-order-service.yaml" \
               -f "$K8S/43-payment-service.yaml" \
               -f "$K8S/44-notification-service.yaml" \
               -f "$K8S/45-analytics-service.yaml" \
               -f "$K8S/50-api-gateway.yaml"

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
