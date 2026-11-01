# Фінальний walkthrough: Quarkus A → B через ArgoCD

Це послідовна перевірка acceptance criteria задачі. Команди виконуються з
кореня clone репозиторію на macOS із передумовами з README. Усі команди
`kubectl` явно вказують context `gitops-demo`.

## 1. Чистий локальний bootstrap — AC5, AC9

Для перевірки шляху без кешованих образів збережіть шлях до робочого
репозиторію, приберіть окремий кластер та перевірте, що немає окремих Docker
контейнерів із образами проєкту:

```sh
PROJECT_ROOT=$PWD
make down
TAG_A=$(python3 scripts/overlay_tag.py read k8s/overlays/local/service-a/kustomization.yaml)
TAG_B=$(python3 scripts/overlay_tag.py read k8s/overlays/local/service-b/kustomization.yaml)
docker ps -a --filter "ancestor=gitops-demo/service-a:$TAG_A" --format '{{.Names}}'
docker ps -a --filter "ancestor=gitops-demo/service-b:$TAG_B" --format '{{.Names}}'
```

Якщо дві останні команди не показують контейнерів, видаліть **тільки ці два
теги** з Docker на хості; `make up` збере їх знову. На справді чистій машині
цей крок не потрібний.

```sh
docker image rm "gitops-demo/service-a:$TAG_A" "gitops-demo/service-b:$TAG_B"
CLONE_DIR=$(mktemp -d)
git clone https://github.com/sokolr/quarkus-gitops-demo.git "$CLONE_DIR/repo"
cd "$CLONE_DIR/repo"
make up
make verify
cd "$PROJECT_ROOT"
```

Очікується збірка обох модулів, завантаження образів, три Applications
`Synced / Healthy` на одному Git SHA, два готові Deployment та HTTP `200`
від A із даними B. `make up` застосовує тільки початковий root Application;
ресурси сервісів створює ArgoCD. Це ізольована перевірка на одній машині;
інший фізичний комп'ютер не перевірявся. Побітова тотожність повторно
зібраних образів не гарантується, як зазначено в ADR.

## 2. API, DNS, probes, correlation ID і тести — AC1, AC2, AC7, AC8

```sh
kubectl --context gitops-demo -n gitops-demo get configmap service-a-config \
  -o jsonpath='{.data.SERVICE_B_URL}'
kubectl --context gitops-demo -n gitops-demo describe deployment service-a
kubectl --context gitops-demo -n gitops-demo describe deployment service-b
kubectl kustomize k8s/base/service-a >/dev/null
kubectl kustomize k8s/base/service-b >/dev/null
kubectl kustomize k8s/overlays/local/service-a >/dev/null
kubectl kustomize k8s/overlays/local/service-b >/dev/null
(cd service-a && mvn clean package)
(cd service-b && mvn clean package)
make verify
```

ConfigMap має містити `http://service-b:8080`. В обох Deployment мають бути
readiness `/q/health/ready` та liveness `/q/health/live`; `make verify`
перевіряє `UP`, HTTP `200` A → B і correlation ID. Чотири команди Kustomize
підтверджують окреме збирання base та local overlays (AC6). Тести запускаються
послідовно: обидва Quarkus-набори використовують порт `8081`. Тести клієнта
A використовують WireMock без запущеного B.

Щоб побачити один ID в обох логах, відкрийте port-forward A в окремому
терміналі та залиште його для перевірки збою. Якщо локальний порт `18082`
зайнятий, виберіть вільний і замініть його в наступних `curl`:

```sh
kubectl --context gitops-demo -n gitops-demo port-forward service/service-a 18082:8080
```

В іншому терміналі з кореня репозиторію:

```sh
curl -si -H 'X-Correlation-ID: walkthrough-123' http://127.0.0.1:18082/api/aggregate
kubectl --context gitops-demo -n gitops-demo logs deployment/service-a --tail=100 | grep 'correlationId=walkthrough-123'
kubectl --context gitops-demo -n gitops-demo logs deployment/service-b --tail=100 | grep 'correlationId=walkthrough-123'
```

Очікуються дані B у JSON A, той самий ID у заголовку відповіді та записи
в обох логах.

## 3. Живий реліз A через Git — AC4, AC5

Перевірте чисту гілку `main`. Для push власнику репозиторію потрібен SSH
доступ до GitHub; якщо clone зроблено через HTTPS, налаштуйте SSH тільки
для push:

```sh
git status --short
git remote set-url --push origin git@github.com:sokolr/quarkus-gitops-demo.git
make release SERVICE=service-a
git diff --check
git diff -- k8s/overlays/local/service-a/kustomization.yaml
```

Скопіюйте **точні** `git add`, `git commit` і `git push`, які виведе
`make release`. До push Deployment A залишається на старому тегу. Після push
дочекайтеся SHA коміту для root, A і B:

```sh
SHA=$(git rev-parse HEAD)
for app in gitops-demo-root service-a service-b; do
  kubectl --context gitops-demo -n argocd wait "application/$app" \
    --for=jsonpath='{.status.sync.revision}'="$SHA" --timeout=600s
done
make verify
kubectl --context gitops-demo -n gitops-demo get deployment service-a \
  -o jsonpath='{.spec.template.spec.containers[0].image}'
```

Очікується новий унікальний тег A в Deployment і HTTP `200` із тим самим
JSON-контрактом. Образ був у Minikube до push; розгортання сталося після
синхронізації ArgoCD.

## 4. Недоступність B і відновлення через Git — AC2, AC3, AC5

У `k8s/overlays/local/service-b/kustomization.yaml` змініть **лише**
`replicas.count: 1` на `0`, перегляньте diff і виконайте:

```sh
kubectl kustomize k8s/overlays/local/service-b >/dev/null
git diff --check
git add k8s/overlays/local/service-b/kustomization.yaml
git commit -m "Demonstrate Service B outage through Git"
git push origin main
SHA=$(git rev-parse HEAD)
kubectl --context gitops-demo -n argocd wait application/service-b \
  --for=jsonpath='{.status.sync.revision}'="$SHA" --timeout=600s
kubectl --context gitops-demo -n gitops-demo wait --for=delete pod \
  -l app=service-b --timeout=120s
kubectl --context gitops-demo -n gitops-demo get deployment service-b
curl -si -H 'X-Correlation-ID: b-down-123' http://127.0.0.1:18082/api/aggregate
curl -si http://127.0.0.1:18082/q/health/ready
kubectl --context gitops-demo -n gitops-demo logs deployment/service-a --tail=100 | grep 'correlationId=b-down-123'
```

B має `0` Pod, A повертає `502`, JSON `SERVICE_B_UNAVAILABLE` і переданий ID;
readiness A залишається `UP`. `make verify` під час збою не запускаємо:
він очікує успішний виклик A → B.

**Відновіть B навіть тоді, коли перевірка збою не вдалася:** поверніть
`replicas.count: 0` на `1` у тому самому файлі та виконайте:

```sh
git diff --check
git add k8s/overlays/local/service-b/kustomization.yaml
git commit -m "Restore Service B after outage demonstration"
git push origin main
SHA=$(git rev-parse HEAD)
for app in gitops-demo-root service-a service-b; do
  kubectl --context gitops-demo -n argocd wait "application/$app" \
    --for=jsonpath='{.status.sync.revision}'="$SHA" --timeout=600s
done
make verify
```

Наприкінці B знову має один готовий Pod, A повертає `200`. Зміна кількості
реплік проходить через Git, бо ArgoCD `selfHeal` може скасувати ручний
`kubectl scale`. Завершіть port-forward A через `Ctrl+C`.

## Підсумок критеріїв

| AC | Доказ у walkthrough |
| --- | --- |
| AC1 | ConfigMap URL `service-b:8080` та успішний виклик A → B у кластері |
| AC2 | Обидва health endpoints, probes і readiness A під час B = 0 |
| AC3 | `502` при B = 0, відновлення `200`; timeout і помилки покриті тестами |
| AC4 | Новий образ A, Git-коміт і зміна тегу Deployment через ArgoCD |
| AC5 | Root та A/B Applications у Git, `Synced / Healthy` на потрібному SHA |
| AC6 | Base і local overlays; окремі overlays для A і B |
| AC7 | Один `X-Correlation-ID` у відповіді та логах обох Pod |
| AC8 | WireMock-тести клієнта A без запущеного B |
| AC9 | Новий clone, профіль, два локально зібрані образи та `make up/verify/down` |
| AC10 | N/A: репозиторій публічний, credentials не потрібні |

## Фактичні результати

Сценарій виконано 1 листопада 2026 року на macOS, Minikube `v1.39.0`,
Kubernetes `v1.37.0`, ArgoCD `v3.5.6`, Java 21 та Quarkus `3.40.1.1`.

| Крок | Фактичний результат |
| --- | --- |
| Чистий clone `b1fca1a` | Після видалення профілю та обох потрібних тегів з Docker `make up` зібрав A і B з коду; `make verify` пройшов. Початкові root, A, B: `Synced / Healthy` на `b1fca1a`. Профіль `minikube` залишився `OK`. |
| Тести | Service A: 6/6, Service B: 9/9; усі без помилок. |
| DNS, probes, ID | ConfigMap: `http://service-b:8080`; у Deployment A і B підключено `/q/health/ready` та `/q/health/live`. ID `walkthrough-123` був у відповіді A та логах A і B. |
| Реліз A `8e90ff2` | Тег `release-20261101214958-b1fca1a4` завантажено до push. До push Deployment мав старий тег; після sync — новий. `make verify` підтвердив HTTP `200`. |
| Збій B `317b08e` | Git задав `replicas: 0`; Pod B зник. A повернув `502`, `{"error":"SERVICE_B_UNAVAILABLE"}`, `X-Correlation-ID: b-down-123`; readiness A відповів `200` і `UP`. У логу A: `reason=CONNECTION_ERROR`. |
| Відновлення B `bd619d4` | Git повернув `replicas: 1`; B став `1/1`, A знову повернув `200`. Root, A і B: `Synced / Healthy` на `bd619d4`; `make verify` пройшов. |

Усі застосунки завершили walkthrough у справному стані. Межа AC9:
перевірено новий clone і чистий локальний профіль на тому самому комп'ютері;
окрему фізичну машину не перевіряли. Образи не мають спільного registry/digest,
тому побітова тотожність повторної локальної збірки не доведена.
