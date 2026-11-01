#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
require_command kubectl
require_command minikube
require_command python3
require_command curl
require_running_profile

log verify 'ArgoCD Applications:'
k -n argocd get applications gitops-demo-root service-a service-b \
  -o custom-columns='NAME:.metadata.name,REVISION:.status.sync.revision,SYNC:.status.sync.status,HEALTH:.status.health.status'
for app in gitops-demo-root service-a service-b; do
  state="$(application_state "$app")"
  [[ "$state" == Synced\ Healthy\ * ]] || die "$app має стан: $state"
done
log verify 'Deployment images і готовність:'
k -n gitops-demo get deployments service-a service-b \
  -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image,READY:.status.readyReplicas,DESIRED:.spec.replicas'
k -n gitops-demo rollout status deployment/service-a --timeout=120s
k -n gitops-demo rollout status deployment/service-b --timeout=120s

tmpdir="$(mktemp -d)"
pids=()
cleanup() {
  for pid in "${pids[@]}"; do
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  done
  rm -rf "$tmpdir"
}
trap cleanup EXIT
start_forward() {
  local service="$1" log_file="$tmpdir/$1.log" port="" pid
  k -n gitops-demo port-forward "service/$service" :8080 --address 127.0.0.1 \
    >"$log_file" 2>&1 &
  pid=$!
  pids+=("$pid")
  for _ in {1..30}; do
    port="$(sed -n 's/.*127\.0\.0\.1:\([0-9][0-9]*\) ->.*/\1/p' "$log_file" | head -n 1)"
    if [[ -n "$port" ]]; then
      PF_PORT="$port"
      return 0
    fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  cat "$log_file" >&2
  die "Не вдалося створити port-forward для $service."
}

start_forward service-a
port_a="$PF_PORT"
start_forward service-b
port_b="$PF_PORT"
for port in "$port_a" "$port_b"; do
  for endpoint in live ready; do
    curl --fail --silent --show-error --max-time 10 \
      "http://127.0.0.1:$port/q/health/$endpoint" \
      | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
  done
done
correlation="verify-$(date -u +%Y%m%d%H%M%S)"
curl --fail --silent --show-error --max-time 10 \
  -D "$tmpdir/headers" -o "$tmpdir/body" \
  -H "X-Correlation-ID: $correlation" \
  "http://127.0.0.1:$port_a/api/aggregate"
python3 - "$tmpdir/headers" "$tmpdir/body" "$correlation" <<'PY'
import json
import sys
from pathlib import Path

headers = Path(sys.argv[1]).read_text().lower()
body = json.loads(Path(sys.argv[2]).read_text())
expected = sys.argv[3]
assert f"x-correlation-id: {expected}" in headers, headers
assert body["service"] == "A" and body["downstream"]["service"] == "B", body
print(f"HTTP 200 A → B; correlation ID: {expected}; JSON: {body}")
PY
log done 'Health і HTTP-виклик A → B успішні.'
