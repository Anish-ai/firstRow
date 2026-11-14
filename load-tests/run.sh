#!/usr/bin/env sh
# Runs the k6 rush test against the docker compose stack, then the SQL correctness checks.
# Prerequisite (from the repo root):  docker compose -f infra/docker-compose.yml up -d --build
# Usage (from the repo root):          sh load-tests/run.sh        (VUS=100 sh load-tests/run.sh to change load)
set -eu

VUS="${VUS:-200}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "== k6: $VUS virtual users =="
# k6 runs in a container on the compose network and calls the backend by its service name.
MSYS_NO_PATHCONV=1 docker run --rm \
  --network firstrow_default \
  -v "$REPO_ROOT/load-tests:/scripts" \
  -e BASE_URL=http://backend:8080 \
  -e VUS="$VUS" \
  grafana/k6:2.3.0 run --summary-export=/scripts/results/summary.json /scripts/rush.js

echo
echo "== SQL correctness checks =="
docker compose -f "$REPO_ROOT/infra/docker-compose.yml" exec -T postgres \
  psql -U firstrow -d firstrow -X < "$REPO_ROOT/load-tests/verify.sql"
