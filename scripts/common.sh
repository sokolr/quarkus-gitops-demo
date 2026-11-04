#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE=gitops-demo
CONTEXT=gitops-demo
ARGOCD_VERSION=v3.5.6
ARGOCD_MANIFEST="https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"
ARGO_ROLLOUTS_VERSION=v1.10.1
ARGO_ROLLOUTS_MANIFEST="https://github.com/argoproj/argo-rollouts/releases/download/${ARGO_ROLLOUTS_VERSION}/install.yaml"
ISTIO_VERSION=1.31.3

log() { printf '[%s] %s\n' "${1}" "${2}"; }
die() { printf 'ПОМИЛКА: %s\n' "$*" >&2; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "Потрібна команда: $1"; }
k() { kubectl --context "$CONTEXT" "$@"; }

require_base_tools() {
  for tool in git java mvn docker minikube kubectl python3 curl; do
    require_command "$tool"
  done
  java -version 2>&1 | head -n 1 | grep -Eq 'version "21([.+-]|\")|openjdk 21([.+-]|$)' \
    || die 'Потрібен JDK 21 (java -version).'
  mvn -version | grep -Eq 'Java version: 21([^0-9]|$)' \
    || die 'Maven має працювати з JDK 21 (mvn -version).'
  docker info >/dev/null 2>&1 || die 'Docker daemon недоступний.'
}

profile_exists() {
  minikube profile list -o json 2>/dev/null | python3 -c \
    'import json,sys; data=json.load(sys.stdin); sys.exit(not any(p.get("Name") == "gitops-demo" for p in data.get("valid", []) + data.get("invalid", [])))'
}

require_running_profile() {
  profile_exists || die "Профіль $PROFILE відсутній. Спочатку виконайте make up."
  local state
  state="$(minikube status -p "$PROFILE" --format='{{.Host}}' 2>/dev/null)" \
    || die "Профіль $PROFILE не запущений. Спочатку виконайте make up."
  [[ "$state" == Running ]] || die "Профіль $PROFILE не запущений (стан: $state)."
  k cluster-info >/dev/null 2>&1 || die "Kubernetes context $CONTEXT недоступний."
}

overlay_for() { printf '%s/k8s/overlays/local/%s/kustomization.yaml' "$ROOT_DIR" "$1"; }
image_for() { printf 'gitops-demo/%s:%s' "$1" "$2"; }
tag_for() { python3 "$ROOT_DIR/scripts/overlay_tag.py" read "$(overlay_for "$1")"; }

application_state() {
  k -n argocd get application "$1" \
    -o jsonpath='{.status.sync.status} {.status.health.status} {.status.sync.revision}' \
    2>/dev/null || true
}

wait_for_applications() {
  local expected_revision="$1" deadline=$((SECONDS + 600)) app state all_ready
  while (( SECONDS < deadline )); do
    all_ready=true
    for app in gitops-demo-root postgres observability service-a service-b; do
      state="$(application_state "$app")"
      if [[ "$state" != "Synced Healthy $expected_revision" ]]; then
        all_ready=false
      fi
    done
    if [[ "$all_ready" == true ]]; then
      return 0
    fi
    sleep 5
  done
  for app in gitops-demo-root postgres observability service-a service-b; do
    printf '%s: %s\n' "$app" "$(application_state "$app")" >&2
  done
  die "ArgoCD не досяг Synced/Healthy на revision $expected_revision за 10 хвилин."
}
