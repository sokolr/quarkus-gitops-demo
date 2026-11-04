#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"
require_command kubectl
require_command minikube
require_command python3
require_command curl
require_command git
require_running_profile
git fetch --quiet origin main || die 'Не вдалося прочитати origin/main.'
expected_revision="$(git rev-parse origin/main)"
[[ "$(git rev-parse HEAD)" == "$expected_revision" ]] \
  || die 'Локальний HEAD відрізняється від origin/main.'

log verify 'ArgoCD Applications:'
k -n argocd get applications gitops-demo-root postgres observability service-a service-b \
  -o custom-columns='NAME:.metadata.name,REVISION:.status.sync.revision,SYNC:.status.sync.status,HEALTH:.status.health.status'
for app in gitops-demo-root postgres observability service-a service-b; do
  state="$(application_state "$app")"
  [[ "$state" == "Synced Healthy $expected_revision" ]] \
    || die "$app має стан: $state; очікувався Synced Healthy $expected_revision"
done
log verify 'PostgreSQL, PVC, Secret і Rollout:'
k -n gitops-demo get statefulset/postgres pvc/data-postgres-0 secret/service-b-db
k -n gitops-demo rollout status statefulset/postgres --timeout=120s
k -n gitops-demo wait --for=jsonpath='{.status.phase}'=Bound pvc/data-postgres-0 --timeout=120s
k -n gitops-demo get rollouts service-a service-b \
  -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image,PHASE:.status.phase,READY:.status.readyReplicas,DESIRED:.spec.replicas'
k -n gitops-demo wait --for=jsonpath='{.status.phase}'=Healthy rollout/service-a --timeout=120s
k -n gitops-demo wait --for=jsonpath='{.status.phase}'=Healthy rollout/service-b --timeout=120s
k -n gitops-demo get virtualservice service-a service-b
log verify 'Prometheus, AnalysisTemplate і генератор трафіку:'
k -n gitops-demo rollout status deployment/prometheus --timeout=120s
k -n gitops-demo rollout status deployment/canary-traffic --timeout=120s
k -n gitops-demo get analysistemplate/canary-success-rate

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
  local namespace="$1" service="$2" target_port="$3" log_file="$tmpdir/$2.log" port="" pid
  k -n "$namespace" port-forward "service/$service" ":$target_port" --address 127.0.0.1 \
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

start_forward gitops-demo service-a-stable 8080
port_a="$PF_PORT"
start_forward gitops-demo service-b-stable 8080
port_b="$PF_PORT"
start_forward istio-system istio-ingressgateway 80
port_gateway="$PF_PORT"
start_forward gitops-demo prometheus 9090
port_prometheus="$PF_PORT"
for port in "$port_a" "$port_b"; do
  for endpoint in live ready; do
    curl --fail --silent --show-error --max-time 10 \
      "http://127.0.0.1:$port/q/health/$endpoint" \
      | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
  done
done
curl --fail --silent --show-error --max-time 10 \
  "http://127.0.0.1:$port_b/api/info" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["service"] == "B" and d["message"]'
correlation="verify-$(date -u +%Y%m%d%H%M%S)"
curl --fail --silent --show-error --max-time 10 \
  -D "$tmpdir/headers" -o "$tmpdir/body" \
  -H 'Host: service-a.demo.local' \
  -H "X-Correlation-ID: $correlation" \
  "http://127.0.0.1:$port_gateway/api/aggregate"
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
curl --fail --silent --show-error --max-time 10 --get \
  --data-urlencode 'query=sum(istio_requests_total{reporter="destination",rollouts_pod_template_hash!=""})' \
  "http://127.0.0.1:$port_prometheus/api/v1/query" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["status"] == "success" and d["data"]["result"] and float(d["data"]["result"][0]["value"][1]) > 0, d'
log done 'Health, HTTP-виклик A → B і метрики Istio в Prometheus успішні.'
