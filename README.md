# Quarkus GitOps demo

Навчальний monorepo для двох Quarkus-сервісів. Service A викликає Service B через HTTP,
обидва мають health endpoints і передають correlation ID. Локальний запуск працює
без Kubernetes.

## Передумови

- JDK 21 (`java -version`).
- Maven 3.9+ (`mvn -version`); Maven має використовувати JDK 21.
- `curl` для ручної перевірки.
- Docker, Minikube та `kubectl` для етапу локального Kubernetes.
- Git і доступ до публічного репозиторію
  [`sokolr/quarkus-gitops-demo`](https://github.com/sokolr/quarkus-gitops-demo) для GitOps.
- Доступ до Maven Central для першого завантаження залежностей.

## Збірка, тести та запуск

Із кореня `quarkus-gitops-demo` зберіть обидва сервіси:

```sh
cd service-b && mvn clean package
cd ../service-a && mvn clean package
```

Запустіть B в першому терміналі з кореня репозиторію:

```sh
cd service-b
java -Dquarkus.http.port=8081 -jar target/quarkus-app/quarkus-run.jar
```

Запустіть A в другому терміналі з кореня репозиторію:

```sh
cd service-a
SERVICE_B_URL=http://localhost:8081 java -jar target/quarkus-app/quarkus-run.jar
```

Збірка запускає тести й створює Quarkus fast-jar. Для запуску потрібна вся
директорія `target/quarkus-app`, а не лише файл `quarkus-run.jar`.
Service A слухає порт `8080`, Service B — `8081`. Зупинка — `Ctrl+C`.

Для розробки з автоматичним перезавантаженням запускайте B на порту `8081`,
а A — на `8080`, кожен зі своєї директорії:

```sh
# service-b
mvn quarkus:dev -Dquarkus.http.port=8081

# service-a (інший термінал)
mvn quarkus:dev
```

Лише тести для поточного модуля:

```sh
mvn test
```

Якщо порт зайнятий, запустіть застосунок на іншому порту, змініть порт у командах
`curl` і, якщо це B, оновіть `SERVICE_B_URL` для A:

```sh
java -Dquarkus.http.port=8082 -jar target/quarkus-app/quarkus-run.jar
```

## API та correlation ID

У третьому терміналі викличте A:

```sh
curl --fail-with-body -i -H 'X-Correlation-ID: demo-123' http://localhost:8080/api/aggregate
```

Очікується HTTP `200`, `X-Correlation-ID: demo-123` і JSON:

```json
{"service":"A","downstream":{"service":"B","message":"Hello from Service B"}}
```

Service A викликає `GET /api/info` Service B через Quarkus REST Client і передає
той самий ID. Обидва сервіси записують ID, метод, шлях і статус у свої логи.
Якщо вхідний ID відсутній або порожній, A генерує UUID і передає його до B.
Контекст ID прив'язаний до HTTP-запиту, тому одночасні запити не змішуються.

Адреса B задана через `quarkus.rest-client.service-b.url`, яку можна перевизначити
змінною `SERVICE_B_URL`. Для майбутнього Deployment значенням стане Kubernetes
Service DNS, наприклад `http://service-b:8080` за умови відповідної назви Service
і порту в manifest. Connect і read timeout — по 1000 мс. A не повторює запит.
Якщо B повертає HTTP-помилку, обриває з'єднання чи відповідає занадто повільно,
A повертає HTTP `502` з JSON `{"error":"SERVICE_B_UNAVAILABLE"}` і тим самим
correlation ID. Після втрати B readiness A залишається `UP`: A працює та повертає
визначену помилку; конкретний виклик перевіряє `/api/aggregate`.
У логах A додатково з'являється причина збою з тим самим ID:
`reason=HTTP_ERROR upstreamStatus=503`, `reason=CONNECTION_ERROR` або
`reason=TIMEOUT`. Зовнішня відповідь однакова для всіх трьох випадків.

Для ручної перевірки недоступності зупиніть B через `Ctrl+C`, залишивши A
запущеним, і виконайте:

```sh
curl -i -H 'X-Correlation-ID: b-down-123' http://localhost:8080/api/aggregate
```

Очікується HTTP `502`, `X-Correlation-ID: b-down-123` і
`{"error":"SERVICE_B_UNAVAILABLE"}`. `--fail-with-body` тут не використовується,
щоб `curl` не трактував очікуваний `502` як помилку команди.

Прямий виклик B:

```sh
curl --fail-with-body -i http://localhost:8081/api/info
```

Очікується HTTP `200`, JSON media type, заголовок `X-Correlation-ID` з UUID та тіло:

```json
{"service":"B","message":"Hello from Service B"}
```

Виклик B із власним ID:

```sh
curl --fail-with-body -i -H 'X-Correlation-ID: demo-123' http://localhost:8081/api/info
```

У відповіді має бути `X-Correlation-ID: demo-123`. У консолі Service B:

```text
Request completed correlationId=demo-123 method=GET path=/api/info status=200
```

Назви HTTP-заголовків нечутливі до регістру. Непорожній ID зберігається;
відсутній, порожній або складений лише з пробілів замінюється новим UUID:

```sh
curl --fail-with-body -i -H 'X-Correlation-ID;' http://localhost:8081/api/info
```

`InfoResponse` — Java record, який визначає JSON-контракт.
`RestController` обробляє `GET /api/info`, а Jackson серіалізує record у JSON.
`CorrelationIdFilter` перед обробкою зберігає ID у властивості поточного HTTP-запиту,
а під час формування відповіді додає заголовок і записує метод, шлях, статус та ID
в лог. Фільтр не зберігає ID у спільному полі чи `ThreadLocal`, тому запити не
передають значення один одному. Лог означає сформовану відповідь, а не
підтвердження її отримання клієнтом.

## Liveness і readiness

```sh
curl --fail-with-body -i http://localhost:8080/q/health/live
curl --fail-with-body -i http://localhost:8080/q/health/ready
curl --fail-with-body -i http://localhost:8081/q/health/live
curl --fail-with-body -i http://localhost:8081/q/health/ready
```

Обидва endpoints працюють на основному HTTP-порту. Після запуску очікується HTTP
`200` та `"status":"UP"`. Їх надає `quarkus-smallrye-health`;
це окремі інфраструктурні маршрути, correlation ID для них не вимагається.

- **Liveness:** чи працює застосунок; майбутня Kubernetes liveness probe допоможе
  визначити, коли контейнер треба перезапустити.
- **Readiness:** чи можна спрямовувати трафік у застосунок; майбутня readiness
  probe визначатиме, чи включати Pod до доступних endpoints Kubernetes Service.

Service B повертає статичний DTO, не має БД, зовнішніх викликів або додаткової
ініціалізації. Service A вміє відповідати визначеною помилкою, коли B недоступний.
Тому використовуємо стандартні health endpoints: запущений HTTP
застосунок може обслуговувати запити. Масив `checks` може бути
порожнім. Це не окрема перевірка бізнес-логіки або навантаження; працездатність API
перевіряють тести та `curl`. При появі обов'язкової залежності слід додати readiness
check її фактичної доступності. Недоступність зовнішньої залежності сама по собі
не повинна спричиняти постійні перезапуски через liveness.

Документація: [Quarkus SmallRye Health](https://quarkus.io/guides/smallrye-health/).

## Автоматична перевірка

`@QuarkusTest` запускає застосунок, а REST Assured перевіряє його HTTP-контракт.
Тести перевіряють JSON-відповідь, обидва health endpoints, повернення та логування
переданого correlation ID, UUID для відсутнього/порожнього ID та ізоляцію ID між
послідовними й паралельними запитами B. Тести A використовують WireMock як HTTP B:
перевіряють успіх, передачу ID, HTTP `503`, розрив з'єднання, timeout і причину
в логах A. Health-тест A запускається без WireMock. Запущений Service B тестам A
не потрібний.

## Docker + Minikube: тимчасова перевірка етапу 3

Цей розділ запускає сервіси у контейнерах і Minikube. Команди `kubectl create`
створюють **тимчасові** ресурси для навчальної перевірки. На наступному етапі їх
замінять Kubernetes manifests; після підключення ArgoCD звичайне розгортання
відбуватиметься через Git, без ручного `kubectl apply`.

Команди нижче виконуються з кореня репозиторію. Збирайте модулі послідовно:
обидва набори Quarkus-тестів використовують порт `8081` і при паралельному
запуску конфліктують.

```sh
(cd service-b && mvn clean package)
(cd service-a && mvn clean package)

TAG=stage3-$(date -u +%Y%m%d%H%M%S)
docker build -t "gitops-demo/service-b:$TAG" service-b
docker build -t "gitops-demo/service-a:$TAG" service-a
```

Збережіть значення `TAG` у тому самому терміналі для подальших команд. Для кожної
нової збірки створюйте новий тег; вже використаний тег не перезаписуйте.
Тег `latest` не використовується. Dockerfile кожного модуля копіює всю
`target/quarkus-app`, запускає Java 21 від непривілейованого користувача та
слухає порт `8080`.

Спершу перевірте образи у спільній Docker-мережі:

```sh
docker network create gitops-demo-stage3
docker run -d --name gitops-demo-b-stage3 --network gitops-demo-stage3 \
  -p 127.0.0.1:18081:8080 "gitops-demo/service-b:$TAG"
docker run -d --name gitops-demo-a-stage3 --network gitops-demo-stage3 \
  -e SERVICE_B_URL=http://gitops-demo-b-stage3:8080 \
  -p 127.0.0.1:18080:8080 "gitops-demo/service-a:$TAG"

curl --retry 10 --retry-connrefused --retry-delay 1 -si http://127.0.0.1:18080/q/health/live
curl -si http://127.0.0.1:18080/q/health/ready
curl --retry 10 --retry-connrefused --retry-delay 1 -si http://127.0.0.1:18081/q/health/live
curl -si http://127.0.0.1:18081/q/health/ready
curl -si -H 'X-Correlation-ID: docker-stage3-123' http://127.0.0.1:18080/api/aggregate
docker logs --tail=20 gitops-demo-a-stage3
docker logs --tail=20 gitops-demo-b-stage3
```

Для health очікуються HTTP `200` і `"status":"UP"`; для `/api/aggregate` —
HTTP `200`, JSON із даними B та `X-Correlation-ID: docker-stage3-123`.
Той самий ID має бути в логах A та B. Після перевірки приберіть контейнери:

```sh
docker rm -f gitops-demo-a-stage3 gitops-demo-b-stage3
docker network rm gitops-demo-stage3
```

Для Minikube використовується наявний профіль із Docker driver та `containerd`.
`minikube image load` переносить локальні образи до runtime кластера без registry.
Перевірте контекст перед створенням ресурсів:

```sh
minikube start -p minikube --driver=docker
kubectl config current-context
minikube image load "gitops-demo/service-b:$TAG" -p minikube
minikube image load "gitops-demo/service-a:$TAG" -p minikube

kubectl create namespace gitops-demo-stage3
kubectl -n gitops-demo-stage3 create deployment service-b \
  --image="gitops-demo/service-b:$TAG" --replicas=1
kubectl -n gitops-demo-stage3 expose deployment service-b --port=8080 --target-port=8080
kubectl -n gitops-demo-stage3 create deployment service-a \
  --image="gitops-demo/service-a:$TAG" --replicas=1
kubectl -n gitops-demo-stage3 set env deployment/service-a SERVICE_B_URL=http://service-b:8080
kubectl -n gitops-demo-stage3 expose deployment service-a --port=8080 --target-port=8080

kubectl -n gitops-demo-stage3 rollout status deployment/service-b --timeout=120s
kubectl -n gitops-demo-stage3 rollout status deployment/service-a --timeout=120s
kubectl -n gitops-demo-stage3 get pods,svc
kubectl -n gitops-demo-stage3 get deployment service-a service-b \
  -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.template.spec.containers[0].imagePullPolicy}{"\n"}{end}'
```

Контекст має бути `minikube`, обидва Pod — `Running`, а policy —
`IfNotPresent`: Kubernetes встановлює її для тегів, відмінних від `latest`.
Обидва сервіси працюють в одному namespace, тому A звертається до B за
Kubernetes Service DNS-іменем `service-b`.

Відкрийте два окремі термінали й залиште port-forward працювати:

```sh
kubectl -n gitops-demo-stage3 port-forward service/service-a 18082:8080 --address 127.0.0.1
```

```sh
kubectl -n gitops-demo-stage3 port-forward service/service-b 18083:8080 --address 127.0.0.1
```

У третьому терміналі перевірте health, виклик і логи Pod:

```sh
curl --retry 10 --retry-connrefused --retry-delay 1 -si http://127.0.0.1:18082/q/health/live
curl -si http://127.0.0.1:18082/q/health/ready
curl --retry 10 --retry-connrefused --retry-delay 1 -si http://127.0.0.1:18083/q/health/live
curl -si http://127.0.0.1:18083/q/health/ready
curl -si -H 'X-Correlation-ID: k8s-stage3-123' http://127.0.0.1:18082/api/aggregate
kubectl -n gitops-demo-stage3 logs deployment/service-a --tail=20
kubectl -n gitops-demo-stage3 logs deployment/service-b --tail=20
```

Очікується HTTP `200`, JSON A з даними B та `k8s-stage3-123` у відповіді й
обох логах. Далі змоделюйте відсутність B і його відновлення:

```sh
kubectl -n gitops-demo-stage3 scale deployment/service-b --replicas=0
kubectl -n gitops-demo-stage3 wait --for=delete pod -l app=service-b --timeout=60s
curl -si -H 'X-Correlation-ID: k8s-b-down-123' http://127.0.0.1:18082/api/aggregate
curl -si http://127.0.0.1:18082/q/health/ready
kubectl -n gitops-demo-stage3 logs deployment/service-a --tail=20

kubectl -n gitops-demo-stage3 scale deployment/service-b --replicas=1
kubectl -n gitops-demo-stage3 rollout status deployment/service-b --timeout=120s
curl -si -H 'X-Correlation-ID: k8s-recovered-123' http://127.0.0.1:18082/api/aggregate
```

Поки B має `0` Pod, A повертає HTTP `502`, `SERVICE_B_UNAVAILABLE` і той самий
correlation ID; readiness A залишається `UP`. Після відновлення B очікується
HTTP `200`. Port-forward B припиниться при видаленні його Pod; за потреби
запустіть його знову після відновлення. Завершіть port-forward через `Ctrl+C` і
видаліть тимчасові ресурси перед наступним етапом:

```sh
kubectl delete namespace gitops-demo-stage3 --wait=true
```

Можна залишити сам Minikube запущеним для наступного етапу або зупинити його
командою `minikube stop -p minikube`.

## Kubernetes manifests + Kustomize: етап 4

`k8s/base` містить спільні Deployment, ClusterIP Service і ConfigMap з
`SERVICE_B_URL=http://service-b:8080`. У кожного Deployment є readiness probe
`/q/health/ready`, liveness probe `/q/health/live`, CPU/memory requests і limits.
`k8s/overlays/local` задає namespace `gitops-demo`, а його підкаталоги
`service-a` і `service-b` — конкретні теги образів для окремих ArgoCD Applications.
Значення `0.0.0` у base — лише заглушка: розгортайте overlay. Якщо збираєте
нові образи, змініть обидва `newTag` у local overlay на **новий** тег і завантажте
ці образи в Minikube перед розгортанням; не перезаписуйте старий тег.

Команди нижче — ручна перевірка manifests до підключення ArgoCD. Після етапу 5
нормальне розгортання має йти через Git і ArgoCD. Із кореня репозиторію:

```sh
kubectl config current-context             # очікується minikube
kubectl kustomize k8s/overlays/local        # перегляд підсумкового YAML
kubectl apply --dry-run=client -k k8s/overlays/local
kubectl apply -k k8s/overlays/local
kubectl -n gitops-demo rollout status deployment/service-b --timeout=120s
kubectl -n gitops-demo rollout status deployment/service-a --timeout=120s
kubectl -n gitops-demo get pods,svc,configmap
```

Обидва Pod мають бути `1/1 Running` без перезапусків. `1/1` означає, що
readiness probe пропустила Pod до трафіку Service. Для перевірки HTTP залиште
port-forward A в окремому терміналі:

```sh
kubectl -n gitops-demo port-forward service/service-a 18082:8080 --address 127.0.0.1
```

У другому терміналі:

```sh
curl -si http://127.0.0.1:18082/q/health/live
curl -si http://127.0.0.1:18082/q/health/ready
curl -si -H 'X-Correlation-ID: stage4-123' http://127.0.0.1:18082/api/aggregate
kubectl -n gitops-demo logs deployment/service-a --tail=20
kubectl -n gitops-demo logs deployment/service-b --tail=20
```

Очікуються `UP`, HTTP `200`, дані B у відповіді A та `stage4-123` у логах обох
Pod. Перевірте збій B і поверніть задекларований стан:

```sh
kubectl -n gitops-demo scale deployment/service-b --replicas=0
kubectl -n gitops-demo wait --for=delete pod -l app=service-b --timeout=60s
curl -si -H 'X-Correlation-ID: stage4-b-down-123' http://127.0.0.1:18082/api/aggregate
curl -si http://127.0.0.1:18082/q/health/ready
kubectl apply -k k8s/overlays/local
kubectl -n gitops-demo rollout status deployment/service-b --timeout=120s
curl -si -H 'X-Correlation-ID: stage4-restored-123' http://127.0.0.1:18082/api/aggregate
```

При B = 0 A повертає `502`, а його readiness залишається `UP`: A може обробити
запит і повернути визначену помилку. Після повторного `apply` B повертається до
одного Pod, і A знову повертає `200`. Liveness не залежить від доступності B, тому
його збій не спричиняє перезапусків A. Перед чистою перевіркою ArgoCD завершіть
port-forward через `Ctrl+C` і приберіть ручне розгортання:

```sh
kubectl delete -k k8s/overlays/local
```

## ArgoCD + GitOps: етап 5

ArgoCD `v3.5.6` встановлюється у namespace `argocd` з офіційного manifest.
`argocd/bootstrap/root-application.yaml` — єдиний Application, який треба
створити під час початкового налаштування. Він читає `argocd/apps` із Git;
звідти ArgoCD створює окремі Applications для A та B. Кожен із них читає свій
Kustomize overlay, автоматично синхронізує зміни і створює namespace
`gitops-demo`, якщо його ще немає. Образи з указаними тегами мають бути
завантажені у Minikube до синхронізації. Для публічного GitHub-репозиторію
облікові дані ArgoCD не потрібні.

Разова перевірка з чистим application namespace (до появи `make up` на етапі 6):

```sh
kubectl config current-context   # minikube
kubectl delete -k k8s/overlays/local --ignore-not-found
kubectl create namespace argocd
kubectl apply -n argocd --server-side --force-conflicts \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/v3.5.6/manifests/install.yaml
kubectl -n argocd rollout status deployment/argocd-server --timeout=180s
kubectl -n argocd rollout status deployment/argocd-repo-server --timeout=180s
kubectl -n argocd rollout status statefulset/argocd-application-controller --timeout=180s
kubectl apply -f argocd/bootstrap/root-application.yaml
kubectl -n argocd get applications
```

Якщо namespace `argocd` уже існує, пропустіть `kubectl create namespace argocd`.
Після синхронізації всі три Applications мають показати `Synced` і `Healthy`, а
обидва Pod — `1/1 Running`:

```sh
kubectl -n argocd get applications
kubectl -n gitops-demo get pods,services
```

Надалі змінюйте бажаний стан у Git: наприклад, оновлюйте `newTag` у
`k8s/overlays/local/service-a/kustomization.yaml` на тег нового образу,
завантажуйте образ у Minikube та робіть `git commit` і `git push`. ArgoCD
підхоплює зміну з Git; ручний `kubectl apply` для сервісів не потрібний.
Повний автоматичний build/tag/load/bootstrap буде додано на етапі 6.

Для простої демонстрації GitOps змініть у
`k8s/overlays/local/service-b/kustomization.yaml` кількість `replicas` з `1`
на `2`, зробіть commit і push. Після синхронізації перевірте:

```sh
kubectl -n argocd get applications
kubectl -n gitops-demo get deployment service-b
kubectl -n gitops-demo get pods -l app=service-b
```

Service B матиме два готові Pod. Поверніть `replicas: 1` наступним Git commit;
ArgoCD поверне Deployment до одного Pod. Зміна через Git доводить
автоматичну синхронізацію manifest, але не є оновленням версії образу.

## Статус acceptance criteria

Нумерація відповідає порядку критеріїв у задачі.

| Критерій | Перевірено | Що залишається |
| --- | --- | --- |
| AC1: зв'язок A → B | URL у ConfigMap; виклик через Kubernetes Service DNS перевірено після Kustomize-розгортання | Повторити у фінальному walkthrough |
| AC2: health і probes обох сервісів | Liveness/readiness probes підключено до Deployment; обидва Pod стали Ready; A лишився Ready при B = 0 | Повторити у фінальному walkthrough |
| AC3: повільний/недоступний B | Timeout і `502` перевірені тестами; B = 0 та відновлення перевірено після Kustomize-розгортання | Повторити у фінальному walkthrough |
| AC6: розділення конфігурації | Спільний base та окремі local overlays A/B перевірені через Kustomize і ArgoCD | Повторити у фінальному walkthrough |
| AC5: ArgoCD | Root і дві Applications зберігаються в Git; A та B синхронізовані з чистого namespace | Повторити у фінальному walkthrough |
| AC7: correlation ID між сервісами | A передає ID до B; той самий ID перевірено в логах обох Pod | Повторити у фінальному walkthrough |
| AC8: тести клієнта | Виконано: WireMock перевіряє успіх і помилки без B | Підтвердити в загальному walkthrough |

Образи A та B на етапі 3 зібрано й запущено з окремим незмінним тегом
`stage3-20261030083352`. AC4 повністю закриється після оновлення версії через
Git commit і синхронізацію ArgoCD.

AC4 (повний GitOps version upgrade) і AC9 (відтворюваний bootstrap) залежать від
наступного етапу. Репозиторій публічний, тому умова AC10 щодо credentials для
приватного Git не застосовується. Скриптів bootstrap/teardown поки немає.
