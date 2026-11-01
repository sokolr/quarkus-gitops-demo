#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
cd "$ROOT_DIR"

service="${1:-}"
[[ "$service" == service-a || "$service" == service-b ]] \
  || die 'Вкажіть SERVICE=service-a або SERVICE=service-b.'
step=preflight
trap 'die "make release зупинився на кроці $step (рядок $LINENO)."' ERR
require_base_tools
require_running_profile
[[ "$(git branch --show-current)" == main ]] || die 'Реліз виконується з гілки main.'
git fetch --quiet origin main || die 'Не вдалося прочитати origin/main.'
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] \
  || die 'Локальний HEAD відрізняється від origin/main.'
python3 - "$service" <<'PY'
import subprocess
import sys

service = sys.argv[1]
lines = subprocess.check_output(
    ["git", "status", "--porcelain=v1", "--untracked-files=all"], text=True
).splitlines()
invalid = [line[3:] for line in lines if not line[3:].startswith(service + "/")]
if invalid:
    raise SystemExit("Сторонні або overlay-зміни перед релізом: " + ", ".join(invalid))
PY

overlay="$(overlay_for "$service")"
old_tag="$(tag_for "$service")"
tag="release-$(date -u +%Y%m%d%H%M%S)-$(git rev-parse --short=8 HEAD)"
image="$(image_for "$service" "$tag")"
docker image inspect "$image" >/dev/null 2>&1 && die "Тег уже існує в Docker: $image"
if minikube -p "$PROFILE" image ls | python3 -c \
  'import sys; image=sys.argv[1]; sys.exit(not any(line.strip() == image or line.strip().endswith("/" + image) for line in sys.stdin))' "$image"; then
  die "Тег уже існує в Minikube: $image"
fi

step=build
log "$step" "Тестую та збираю $service з новим тегом $tag."
(cd "$service" && mvn clean package)
docker build -t "$image" "$service"

step=image_load
minikube -p "$PROFILE" image load --overwrite=false "$image"

step=overlay
log "$step" "Оновлюю тег $old_tag → $tag."
backup="$(mktemp)"
cp "$overlay" "$backup"
updated=false
cleanup() {
  if [[ "$updated" != true ]]; then
    cp "$backup" "$overlay"
  fi
  rm -f "$backup"
}
trap cleanup EXIT
python3 scripts/overlay_tag.py replace "$overlay" "$old_tag" "$tag"
kubectl kustomize "$(dirname "$overlay")" >/dev/null
git diff --check -- "$overlay"
updated=true
log done "Образ $image завантажено. Перевірте diff і виконайте Git-кроки вручну:"
git diff -- "$overlay"
printf '\n  git add -- %s k8s/overlays/local/%s/kustomization.yaml\n' "$service" "$service"
printf '  git commit -m "Release %s %s"\n' "$service" "$tag"
printf '  git push origin main\n'
printf '  make verify\n'
