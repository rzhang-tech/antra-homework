#!/usr/bin/env bash
#
# Build every service image. Run from anywhere:
#
#   ./scripts/build-images.sh                # tag :latest
#   ./scripts/build-images.sh v1.2.0         # tag :v1.2.0 as well as :latest
#
# Eight images, one per deployable. cover-processor is not here: it is a Lambda, deployed as a jar by
# scripts/aws/deploy-cover-processor.sh, and putting it in a container would mean running a container
# to do the thing Lambda exists to do without one.
#
# Compose builds these itself (10b) and this script exists for the two cases compose does not cover:
# building without starting anything, and tagging a release for a registry — which is what Step 11's
# pipeline will do, one image per commit SHA.

set -euo pipefail

cd "$(dirname "$0")/../bookstore-platform"

TAG="${1:-}"
SERVICES=(
  config-server
  api-gateway
  user-service
  book-service
  order-service
  payment-service
  notification-service
  analytics-service
)

start=$(date +%s)
for svc in "${SERVICES[@]}"; do
  printf '%-22s ' "$svc"
  # The build context is bookstore-platform/ for every service, because each build needs the parent
  # pom that its own pom inherits from. .dockerignore keeps nine target/ directories out of it.
  docker build -q -f "$svc/Dockerfile" -t "bookstore/$svc:latest" . > /dev/null
  if [[ -n "$TAG" ]]; then
    docker tag "bookstore/$svc:latest" "bookstore/$svc:$TAG"
    echo "built  bookstore/$svc:{latest,$TAG}"
  else
    echo "built  bookstore/$svc:latest"
  fi
done

echo
echo "$(( $(date +%s) - start ))s"
docker images --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' | grep '^bookstore/' | grep ':latest' | sort
