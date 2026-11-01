#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
require_command minikube
require_command python3

if profile_exists; then
  log down "Видаляю лише профіль $PROFILE."
  minikube delete -p "$PROFILE" || die "Не вдалося видалити профіль $PROFILE."
else
  log down "Профіль $PROFILE уже відсутній."
fi
