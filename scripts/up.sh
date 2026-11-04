#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"

step=preflight
trap 'die "make up зупинився на кроці $step (рядок $LINENO)."' ERR
log "$step" 'Перевіряю інструменти, Git і Docker.'
require_base_tools
require_command openssl
require_command tar
[[ "$(git branch --show-current)" == main ]] || die 'make up потребує гілку main.'
[[ -z "$(git status --porcelain=v1 --untracked-files=all)" ]] \
  || die 'make up потребує чистий Git checkout.'
git fetch --quiet origin main || die 'Не вдалося прочитати origin/main.'
revision="$(git rev-parse HEAD)"
[[ "$revision" == "$(git rev-parse origin/main)" ]] \
  || die 'Локальний HEAD відрізняється від origin/main. Синхронізуйте Git перед make up.'

step=images
for service in service-b service-a; do
  tag="$(tag_for "$service")"
  image="$(image_for "$service" "$tag")"
  if docker image inspect "$image" >/dev/null 2>&1; then
    log "$step" "Використовую наявний образ $image."
  else
    log "$step" "Тестую і збираю $service для $image."
    (cd "$service" && mvn clean package)
    docker build -t "$image" "$service"
  fi
done

step=minikube
log "$step" "Запускаю окремий профіль $PROFILE (4 CPU, 7168 MB)."
minikube start -p "$PROFILE" --driver=docker --container-runtime=containerd \
  --cpus=4 --memory=7168
require_running_profile

step=secret
if ! k get namespace gitops-demo >/dev/null 2>&1; then
  k create namespace gitops-demo
fi
k label namespace gitops-demo istio-injection=enabled --overwrite >/dev/null
if k -n gitops-demo get secret service-b-db >/dev/null 2>&1; then
  log "$step" 'Використовую наявний Secret service-b-db.'
else
  if k -n gitops-demo get pvc data-postgres-0 >/dev/null 2>&1; then
    die 'PVC PostgreSQL існує без Secret service-b-db; відновіть старі credentials перед запуском.'
  fi
  log "$step" 'Створюю Secret service-b-db; пароль не записується в Git або лог.'
  if [[ -n "${DB_PASSWORD:-}" ]]; then
    printf %s "$DB_PASSWORD" | k -n gitops-demo create secret generic service-b-db \
      --from-literal=username=service_b --from-file=password=/dev/stdin
  else
    openssl rand -hex 32 | tr -d '\n' | k -n gitops-demo create secret generic service-b-db \
      --from-literal=username=service_b --from-file=password=/dev/stdin
  fi
fi

step=image_load
for service in service-b service-a; do
  image="$(image_for "$service" "$(tag_for "$service")")"
  log "$step" "Завантажую $image у $PROFILE."
  minikube -p "$PROFILE" image load --overwrite=false "$image"
done

step=rollouts
log "$step" "Встановлюю Argo Rollouts $ARGO_ROLLOUTS_VERSION."
if ! k get namespace argo-rollouts >/dev/null 2>&1; then
  k create namespace argo-rollouts
fi
k apply -n argo-rollouts --server-side -f "$ARGO_ROLLOUTS_MANIFEST"
k wait --for=condition=Established crd/rollouts.argoproj.io --timeout=180s
k wait --for=condition=Established crd/analysistemplates.argoproj.io --timeout=180s
k -n argo-rollouts rollout status deployment/argo-rollouts --timeout=300s

step=istio
log "$step" "Встановлюю Istio $ISTIO_VERSION."
istioctl_dir=''
cleanup_istioctl() {
  if [[ -n "$istioctl_dir" ]]; then
    rm -rf "$istioctl_dir"
  fi
}
trap cleanup_istioctl EXIT
istioctl_bin="$(command -v istioctl || true)"
if [[ -z "$istioctl_bin" ]] || ! "$istioctl_bin" version --remote=false 2>/dev/null | grep -q "$ISTIO_VERSION"; then
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) platform=osx-arm64; checksum=8ade5199bcd96942c139182f47f910426f09cf36cd4167fa8c1fdf14a95ddc59 ;;
    Darwin-x86_64) platform=osx-amd64; checksum=a44bf341bcc9c6112ea1b4ce5a18cb996ae501527db2a2b0d8ec0564254eba5c ;;
    Linux-aarch64|Linux-arm64) platform=linux-arm64; checksum=e7efaf26b6ad60d878994d1ed6febccc11dc291962a92857e53946e663e9bb53 ;;
    Linux-x86_64) platform=linux-amd64; checksum=b32bba09bc6138d738fd19a09ef6d0fcdd5c5c013efeb72969663c24a2bddd47 ;;
    *) die 'Непідтримувана платформа для istioctl.' ;;
  esac
  istioctl_dir="$(mktemp -d)"
  archive="$istioctl_dir/istio.tar.gz"
  curl --fail --location --silent --show-error \
    "https://github.com/istio/istio/releases/download/$ISTIO_VERSION/istio-$ISTIO_VERSION-$platform.tar.gz" \
    -o "$archive"
  actual="$(openssl dgst -sha256 "$archive" | awk '{print $NF}')"
  [[ "$actual" == "$checksum" ]] || die 'SHA-256 архіву istioctl не збігається.'
  tar -xzf "$archive" -C "$istioctl_dir" "istio-$ISTIO_VERSION/bin/istioctl"
  istioctl_bin="$istioctl_dir/istio-$ISTIO_VERSION/bin/istioctl"
fi
"$istioctl_bin" --context "$CONTEXT" install --set profile=default \
  --set values.global.platform=minikube --skip-confirmation
k -n istio-system rollout status deployment/istiod --timeout=300s
k -n istio-system rollout status deployment/istio-ingressgateway --timeout=300s

step=argocd
log "$step" "Встановлюю ArgoCD $ARGOCD_VERSION."
if ! k get namespace argocd >/dev/null 2>&1; then
  k create namespace argocd
fi
k apply -n argocd --server-side --force-conflicts -f "$ARGOCD_MANIFEST"
k wait --for=condition=Established crd/applications.argoproj.io --timeout=180s
k -n argocd rollout status deployment/argocd-server --timeout=300s
k -n argocd rollout status deployment/argocd-repo-server --timeout=300s
k -n argocd rollout status statefulset/argocd-application-controller --timeout=300s

polling="$(k -n argocd get configmap argocd-cm \
  -o jsonpath='{.data.timeout\.reconciliation} {.data.timeout\.reconciliation\.jitter}')"
if [[ "$polling" != '30s 0s' ]]; then
  log "$step" 'Налаштовую перевірку Git кожні 30 секунд без jitter.'
  k -n argocd patch configmap argocd-cm --type merge \
    -p '{"data":{"timeout.reconciliation":"30s","timeout.reconciliation.jitter":"0s"}}'
  k -n argocd rollout restart statefulset/argocd-application-controller
  k -n argocd rollout restart deployment/argocd-repo-server
  k -n argocd rollout status statefulset/argocd-application-controller --timeout=300s
  k -n argocd rollout status deployment/argocd-repo-server --timeout=300s
else
  log "$step" 'Інтервал уже 30 секунд; перезапуск ArgoCD не потрібний.'
fi

step=postgres
log "$step" 'Синхронізую PostgreSQL через ArgoCD до запуску Service B.'
k apply -f argocd/apps/postgres.yaml
postgres_deadline=$((SECONDS + 600))
postgres_state=''
while (( SECONDS < postgres_deadline )); do
  postgres_state="$(application_state postgres)"
  [[ "$postgres_state" == "Synced Healthy $revision" ]] && break
  sleep 5
done
[[ "$postgres_state" == "Synced Healthy $revision" ]] \
  || die "PostgreSQL Application не готова: $postgres_state"
k -n gitops-demo rollout status statefulset/postgres --timeout=180s

step=observability
log "$step" 'Синхронізую Prometheus, AnalysisTemplate та тестовий трафік до запуску Rollout.'
k apply -f argocd/apps/observability.yaml
observability_deadline=$((SECONDS + 600))
observability_state=''
while (( SECONDS < observability_deadline )); do
  observability_state="$(application_state observability)"
  [[ "$observability_state" == "Synced Healthy $revision" ]] && break
  sleep 5
done
[[ "$observability_state" == "Synced Healthy $revision" ]] \
  || die "Observability Application не готова: $observability_state"
k -n gitops-demo rollout status deployment/prometheus --timeout=180s

step=root_application
log "$step" 'Створюю root Application; PostgreSQL і сервіси синхронізує ArgoCD.'
k apply -f argocd/bootstrap/root-application.yaml

step=sync
log "$step" "Чекаю Synced/Healthy для revision $revision."
wait_for_applications "$revision"
k -n gitops-demo rollout status statefulset/postgres --timeout=180s
log done "PostgreSQL, Prometheus і обидва Rollout готові в профілі $PROFILE. Виконайте make verify."
