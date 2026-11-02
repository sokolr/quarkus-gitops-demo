#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"

step=preflight
trap 'die "make up зупинився на кроці $step (рядок $LINENO)."' ERR
log "$step" 'Перевіряю інструменти, Git і Docker.'
require_base_tools
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
log "$step" "Запускаю окремий профіль $PROFILE (2 CPU, 4096 MB)."
minikube start -p "$PROFILE" --driver=docker --container-runtime=containerd \
  --cpus=2 --memory=4096
require_running_profile

step=image_load
for service in service-b service-a; do
  image="$(image_for "$service" "$(tag_for "$service")")"
  log "$step" "Завантажую $image у $PROFILE."
  minikube -p "$PROFILE" image load --overwrite=false "$image"
done

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

step=root_application
log "$step" 'Створюю root Application; сервіси синхронізує ArgoCD.'
k apply -f argocd/bootstrap/root-application.yaml

step=sync
log "$step" "Чекаю Synced/Healthy для revision $revision."
wait_for_applications "$revision"
k -n gitops-demo rollout status deployment/service-b --timeout=180s
k -n gitops-demo rollout status deployment/service-a --timeout=180s
log done "Обидва сервіси готові в профілі $PROFILE. Виконайте make verify."
