# Quarkus GitOps demo

Навчальний monorepo для двох Quarkus-сервісів. Service A викликає Service B через HTTP,
обидва мають health endpoints і передають correlation ID. Локальний запуск працює
без Kubernetes.

## Task 2, етап 1: дані та API

Service B тепер зберігає повідомлення в PostgreSQL. `GET /api/info` зберігає
поля `{ "service": "B", "message": "..." }`, але читає `message` з БД. Service A
тому не потребує змін. `PUT /api/info/message` з JSON
`{ "message": "Новий текст" }` замінює повідомлення й повертає оновлений
`InfoResponse`. Порожнє повідомлення або текст довший за 500 символів дає
HTTP `400`.

```sh
cd service-b
mvn test
mvn quarkus:dev -Dquarkus.http.port=8081

# в іншому терміналі
curl -s http://localhost:8081/api/info
curl -s -X PUT http://localhost:8081/api/info/message \
  -H 'Content-Type: application/json' \
  -d '{"message":"Збережено в PostgreSQL"}'
curl -s http://localhost:8081/api/info
```

Для тестів і dev-режиму потрібен запущений Docker: Quarkus Dev Services
піднімає тимчасовий PostgreSQL. У production-режимі Service B читає
`DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD` з середовища; значення не зберігаються
в репозиторії. Flyway на старті застосовує версійну міграцію
`V1__create_service_message.sql`, яка створює таблицю та початковий рядок.
Пул з'єднань обмежено п'ятьма на Pod. Вбудована перевірка Agroal позначає
`/q/health/ready` як `DOWN`, коли БД недоступна; liveness не залежить від БД.
Якщо B не може прочитати повідомлення, його API повертає помилку, а Service A
перетворює помилку B на HTTP `502`.

У кластері Service B отримує адресу БД та credentials із Kubernetes Secret;
опис bootstrap наведено в етапі 3 нижче.

## Task 2, етап 2: сумісна міграція V2

Файл `X2__add_service_description.sql` додає nullable-колонку
`description VARCHAR(500)`. Він не змінює й не видаляє `message`. V2 Service B
повертає `description` у `GET /api/info`, коли її встановлено. Для запису:

```sh
curl -s -X PUT http://localhost:8081/api/info/description \
  -H 'Content-Type: application/json' \
  -d '{"description":"Опис версії 2"}'
curl -s http://localhost:8081/api/info
```

`description` може залишатися `NULL` для старих рядків; API приймає непорожній
текст до 500 символів. Коли значення `NULL`, поле не включається до JSON, тому
відповідь має попередню форму. Service A ігнорує додаткове поле у відповіді B.

Послідовність релізу: спочатку працює образ B V1 із міграцією V1; потім перший
Pod B V2 застосовує V2 до спільної БД і починає canary. Поки V1 і V2 працюють
одночасно, V1 читає/пише `message`, а нова колонка залишається необов'язковою.
Якщо V2 відкочено, схема лишається V2: Flyway у V1 сприймає її як майбутню
версію, а старий SQL і далі працює. Відкат застосунку не запускає `flyway clean`
або зворотну міграцію. Для демонстрації rollout збережіть окремий образ V1
до побудови образу V2.

У цьому проєкті Flyway запускається під час старту кожного Pod. Якщо кілька
нових Pod стартують одночасно, Flyway координує їх блокуванням БД: міграцію
виконує один Pod, інші чекають і потім перевіряють уже застосовану версію.
До завершення міграцій Pod не стає Ready. Це підходить для короткої
транзакційної зміни `ADD COLUMN`; довгі або нетранзакційні зміни варто виносити
в окремий крок релізу.

У межах одного релізу безпечно додавати nullable-колонки, які старий код не
використовує. `DROP`, перейменування чи нове обмеження `NOT NULL` для поля,
яке пише V1, потребують кількох релізів: спершу додати нову колонку, далі
перевести всіх читачів і письменників, потім дочекатися завершення періоду
відкату й лише окремою міграцією прибрати стару колонку.

## Task 2, етап 3: PostgreSQL, Secret, Rollouts та Istio

`make up` створює профіль Minikube `gitops-demo` із 4 CPU та 7168 МБ пам'яті,
встановлює Argo Rollouts `v1.10.1` та Istio `1.31.3`, а потім запускає ArgoCD.
Перед запуском зміни мають бути закомічені й відправлені в `origin/main`:
ArgoCD читає GitHub, а `make up` перевіряє збіг локального та віддаленого commit.
ArgoCD керує PostgreSQL StatefulSet, PVC та двома Rollout. PostgreSQL 18
зберігає дані на PVC `data-postgres-0` (2 GiB, стандартний StorageClass).
Перезапуск Pod PostgreSQL чи Service B не видаляє PVC. `make down` видаляє весь
профіль Minikube разом із даними — це повне прибирання середовища.

Ім'я користувача й пароль БД генеруються під час першого `make up`; команда створює
Kubernetes Secret `service-b-db` із ключами `username` і `password` у namespace
`gitops-demo`. Повторний `make up` використовує наявний Secret. Якщо PVC вже є,
а Secret втрачено, bootstrap зупиняється, щоб не створити пароль, який не
відповідає існуючій БД. За потреби значення можна подати через `DB_USERNAME`
і `DB_PASSWORD` у середовищі до першого запуску. Credentials не додаються в
Git і не друкуються в лог.
Kubernetes Secret — сховище всередині кластера, не зовнішній Vault; значення
Secret за замовчуванням не шифруються в etcd. Для цього локального прикладу
обрано дозволений задачею bootstrap Secret; для production потрібне окреме
керування ключами та шифрування Secret.

Пул Service B обмежено 5 з'єднаннями на Pod (`quarkus.datasource.jdbc.max-size=5`).
З Istio traffic routing stable ReplicaSet зберігає 2 Pod, а canary може мати
ще 2 Pod. Отже під час одного rollout верхня межа для застосунку —
**4 × 5 = 20 з'єднань**. `maxSurge: 1` тут не обмежує сумарну кількість Pod:
Argo Rollouts не використовує його для розрахунку stable/canary replicas із
traffic routing. При швидких послідовних rollout старі ReplicaSet можуть
тимчасово лишатися запущеними, тому перед новим rollout перевіряйте фактичні
Pod і залишайте додатковий резерв. PostgreSQL 18 зазвичай має
`max_connections=100`, але фактичне значення залежить від ініціалізації
кластера. Перевірте його командою нижче; 20 не включає адміністративні й
інші клієнтські з'єднання:

```sh
kubectl --context gitops-demo -n gitops-demo exec postgres-0 -- sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SHOW max_connections"'
```

Для перевірки збереження даних запишіть у B новий `message` через REST,
перезапустіть Pod B, потім Pod PostgreSQL і прочитайте повідомлення знову.
PVC `data-postgres-0` має залишитися `Bound` після обох перезапусків.

Для демонстрації недоступності БД змініть `spec.replicas` PostgreSQL StatefulSet
з `1` на `0` у Git, зробіть commit/push і дочекайтеся синхронізації ArgoCD.
Тоді readiness B стане `DOWN`; readiness A залишиться `UP`, але
`GET /api/aggregate` поверне `502` з `SERVICE_B_UNAVAILABLE`. Поверніть
`replicas: 1` окремим commit/push і перевірте відновлення через `make verify`.
Безпосередній `kubectl scale` тут ненадійний для демонстрації, бо ArgoCD із
`selfHeal` поверне бажане значення з Git. Зміна кількості Pod не видаляє PVC.

```sh
make up
make verify
kubectl --context gitops-demo -n gitops-demo get statefulset/postgres pvc/data-postgres-0 secret/service-b-db
kubectl --context gitops-demo -n gitops-demo get rollouts service-a service-b
kubectl --context gitops-demo -n gitops-demo get virtualservice service-a service-b
```

Обидва Rollout мають 2 базові репліки та canary-кроки 10%, 25%, 50%, 100% із
автоматичним аналізом після 10%, 25% і 50%. Argo Rollouts змінює ваги в Istio
VirtualService; stable та canary Service вибирають відповідні ReplicaSet.
`service-b` спрямовує
запити A → B через sidecar, `service-a.demo.local` — вхідні запити через
Istio ingress gateway. Ваги задають імовірність маршрутизації кожного запиту,
тому маленька вибірка не обов'язково дасть рівно 10/25/50%. Прямий port-forward
до Service або Pod обходить Istio та не перевіряє canary-ваги.

```sh
kubectl --context gitops-demo -n istio-system port-forward service/istio-ingressgateway 18080:80
# в іншому терміналі
curl -H 'Host: service-a.demo.local' http://127.0.0.1:18080/api/aggregate
```

Щоб побачити поточні ваги A → B, повторюйте під час rollout:

```sh
kubectl --context gitops-demo -n gitops-demo get virtualservice/service-b \
  -o jsonpath='{range .spec.http[0].route[*]}{.destination.host}={.weight}%{" "}{end}{"\n"}'
kubectl --context gitops-demo -n gitops-demo get analysisruns
```

Очікувані ваги stable/canary: `90/10` → `75/25` → `50/50` → `100/0`.
Наприкінці stable Service вже вказує на **новий** ReplicaSet. Початкове
створення Rollout не проходить canary-кроки: вони спрацьовують при наступній
зміні шаблону Pod, наприклад тега образу або `DEMO_FAULT_PERCENT`.

ArgoCD ігнорує поля ваг VirtualService та hash-селектор stable/canary Service,
якими керує Rollouts; це запобігає конфлікту між reconciler-ами. Під час
аналізу Rollout має стан `Progressing`; після успішного завершення —
`Healthy`. Невдалий аналіз автоматично повертає трафік на stable та позначає
Rollout і ArgoCD Application як `Degraded` до повернення бажаного стану в Git.
Для демонстрації V1 → V2 потрібні два різні образи B: образ V1 слід зберегти
до оновлення overlay на образ V2.

## Task 2, етап 4: автоматичний аналіз і відкат

ArgoCD Application `observability` встановлює локальний Prometheus, `AnalysisTemplate`
та Pod `canary-traffic`. Prometheus кожні 10 секунд збирає з Istio sidecar
метрику `istio_requests_total` для A і B. Генератор надсилає приблизно 5
запитів/с до A через mesh; A викликає B. Він потрібен, щоб canary мав дані
навіть без ручних запитів. Це навантаження лише для демонстрації.

Після встановлення 10%, 25% і 50% Argo Rollouts запускає окремий AnalysisRun. Він
чекає 45 секунд на накопичення метрик і тричі з інтервалом 15 секунд вимірює
частку HTTP-відповідей без 5xx **саме Pod нового ReplicaSet** за останню хвилину.
Усі три виміри мають бути не нижче 95%. Будь-який вимір нижче 95%, включно
з відсутністю запитів, провалює аналіз і автоматично повертає 100% трафіку
на stable. Під час недоступності Prometheus аналіз не може підтвердити успіх;
перевіряйте AnalysisRun замість примусового promotion.

Кожен успішний аналіз займає приблизно 75–90 секунд; три аналізи — близько
4 хвилин плюс запуск Pod та синхронізація ArgoCD. Саме оновлення ваги Istio
після завершення кроку зазвичай займає секунди.

```sh
kubectl --context gitops-demo -n gitops-demo get deployment/prometheus deployment/canary-traffic analysistemplate/canary-success-rate
kubectl --context gitops-demo -n gitops-demo get analysisruns
kubectl --context gitops-demo -n gitops-demo describe rollout/service-b
kubectl --context gitops-demo -n gitops-demo port-forward service/prometheus 9090:9090
# Prometheus UI: http://127.0.0.1:9090
```

Щоб продемонструвати автоматичний abort, спочатку дочекайтеся `Healthy` для
Service B з `DEMO_FAULT_PERCENT=0`. Далі змініть лише `value: "0"` на `"100"`
у `k8s/overlays/local/service-b/fault.yaml`, закомітьте й відправте зміну в
`origin/main`. Ця зміна Pod template створює новий canary; його `GET /api/info`
повертає 503 у 100% випадків, але readiness залишається `UP`. Контролер
повинен створити `AnalysisRun` зі станом `Failed`, зробити `Aborted` для B та
повернути вагу canary на 0. Перевірте:

```sh
kubectl --context gitops-demo -n gitops-demo get analysisruns
kubectl --context gitops-demo -n gitops-demo get rollout/service-b virtualservice/service-b
kubectl --context gitops-demo -n argocd get application/service-b
```

ArgoCD лишається `Synced`, бо бажана несправна конфігурація все ще в Git;
здоров'я Application стає `Degraded`. Rollouts не записує відкат у Git і не
перезапускає несправний canary у циклі. Щоб закрити інцидент, поверніть у
`fault.yaml` значення `"0"`, закомітьте та відправте зміни. У цьому демо
ввімкнення помилки змінює конфігурацію нового Pod, а не код образу; для
окремих релізів V1/V2 використовуйте різні теги образів.

## Передумови

- JDK 21 (`java -version`).
- Maven 3.9+ (`mvn -version`); Maven має використовувати JDK 21.
- `curl` для ручної перевірки.
- Docker, Minikube та `kubectl` для етапу локального Kubernetes.
- Python 3 і `make` для автоматизованого bootstrap етапу 6.
- Для окремого профілю `gitops-demo` виділіть Docker щонайменше 8 ГБ пам'яті;
  якщо паралельно працює інший профіль Minikube, може знадобитися більше.
- Git і доступ до публічного репозиторію
  [`sokolr/quarkus-gitops-demo`](https://github.com/sokolr/quarkus-gitops-demo) для GitOps.
- Доступ до Maven Central для першого завантаження залежностей.

## Швидкий запуск GitOps

Із чистого clone публічного репозиторію та запущеним Docker:

```sh
git clone https://github.com/sokolr/quarkus-gitops-demo.git
cd quarkus-gitops-demo
make up
make verify
```

`make up` збирає відсутні образи, створює **окремий** профіль Minikube
`gitops-demo` і чекає `Synced / Healthy` від ArgoCD. `make down` видаляє цей
профіль разом із PostgreSQL PVC. Зміни застосунків надалі проходять через Git commit/push;
одноразове створення root Application входить до bootstrap.

Коли закінчите роботу з кластером, виконайте `make down`.

Повний сценарій усіх acceptance criteria наведено у
[walkthrough](docs/walkthrough.md), а причини архітектурних рішень — в
[ADR](docs/adr/0001-local-gitops.md). Навчальні ручні сценарії Task 1 нижче
описують попередні Deployment і не є інструкцією для поточних Rollout;
для звичайного запуску використовуйте команди вище.

## Збірка, тести та запуск

Із кореня `quarkus-gitops-demo` зберіть обидва сервіси:

```sh
cd service-b && mvn clean package
cd ../service-a && mvn clean package
```

Запустіть B в першому терміналі з кореня репозиторію:

```sh
cd service-b
mvn quarkus:dev -Dquarkus.http.port=8081
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

Історична ручна перевірка з чистим application namespace до автоматизованого bootstrap:

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
Автоматизований build/tag/load/bootstrap описано в розділі етапу 6 нижче.

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

## Відтворюваний bootstrap і реліз: етап 6

З чистої машини встановіть передумови вище, запустіть Docker і клонуйте
[публічний репозиторій](https://github.com/sokolr/quarkus-gitops-demo):

```sh
git clone https://github.com/sokolr/quarkus-gitops-demo.git
cd quarkus-gitops-demo
make up
make verify
```

`make up` вимагає чисту гілку `main`, що збігається з `origin/main`.
Він перевіряє Java 21, Maven і Docker; бере теги образів із local overlays;
збирає відсутні образи з Quarkus fast-jar і завантажує їх у Minikube. Наявні
локальні теги не перезаписує. Окремий профіль `gitops-demo` створюється з
Docker driver, `containerd`, 2 CPU та 4096 МБ пам'яті. Команда встановлює
ArgoCD `v3.5.6`, одноразово створює root Application і чекає, доки root, A та B
будуть `Synced / Healthy` на поточному Git commit, а обидва Deployment готові.
Bootstrap задає ArgoCD перевірку Git кожні `30s` без додаткового jitter;
при зміні параметрів перезапускає application-controller і repo-server.
Повторний `make up` безпечний. Усі команди Kubernetes використовують явний
context `gitops-demo`; профіль `minikube` з попередніх етапів не змінюється.

`make verify` звіряє revision усіх Applications з `origin/main`, показує їхні
стани, теги образів і готовність Deployment. Через тимчасові port-forward він перевіряє liveness/readiness
обох сервісів і HTTP-виклик A → B з correlation ID. Port-forward автоматично
закривається наприкінці перевірки.

Щоб підготувати нову версію одного сервісу, за потреби змініть його код і
тести, а потім виконайте:

```sh
make release SERVICE=service-b
git diff
git add -- service-b k8s/overlays/local/service-b/kustomization.yaml
git commit -m "Release service-b <тег, показаний make release>"
git push origin main
```

`make release` перевіряє, що профіль запущений, `HEAD` відповідає
`origin/main`, а зміни робочого дерева належать лише вибраному модулю.
Він запускає тести, створює унікальний тег `release-<UTC-час>-<Git-SHA>`,
будує й завантажує образ, змінює тільки `newTag` відповідного overlay та
перевіряє Kustomize. Команда друкує точні команди Git зі справжнім тегом;
приклад вище показує порядок дій. Commit і push виконуються окремо після
перегляду diff. ArgoCD сам побачить зміну в Git; `kubectl apply` для сервісу
не потрібний. Дочекайтеся синхронізації та повторіть `make verify`.

Для повного прибирання лише цього навчального кластера:

```sh
make down
make down  # повторний виклик також безпечний
```

`make down` видаляє тільки профіль `gitops-demo` разом з ArgoCD і Pod.
Профіль `minikube`, Git-репозиторій та образи Docker на хості залишаються.
Після `make down` команда `make up` відтворить стан із GitHub. Для першого
завантаження Maven-залежностей, базового Docker-образу, Minikube та manifest
ArgoCD потрібен доступ до інтернету. Локальна збірка образу на іншій машині
може дати інші байти під тим самим тегом; для точного збігу потрібен спільний
registry з образом за digest.

## Статус acceptance criteria

Нумерація відповідає порядку критеріїв у задачі.

| Критерій | Перевірено | Що залишається |
| --- | --- | --- |
| AC1: зв'язок A → B | ConfigMap задає Service DNS; A повернув дані B у кластері | Перевірено локально |
| AC2: health і probes обох сервісів | Probes підключені; A лишився Ready при B = 0 | Перевірено локально |
| AC3: повільний/недоступний B | Timeout і помилки покриті тестами; Git-масштабування B до 0 дало `502`, відновлення — `200` | Перевірено локально |
| AC4: новий тег образу | Релізи B `12bc4f0` і A `8e90ff2` перейшли в Deployment через Git та ArgoCD | Теги унікальні; побітова тотожність між машинами не доведена |
| AC5: ArgoCD | Root і дві Applications у Git; після відновлення B всі `Synced / Healthy` на `bd619d4` | Перевірено локально |
| AC6: розділення конфігурації | Base і окремі local overlays A/B зібрані Kustomize | Перевірено локально |
| AC7: correlation ID між сервісами | `walkthrough-123` у відповіді та логах A і B | Перевірено локально |
| AC8: тести клієнта | WireMock перевіряє успіх і помилки без B; тести A 6/6, B 9/9 | Перевірено локально |
| AC9: чистий bootstrap | Новий clone, профіль і збірка обох відсутніх образів через `make up/verify` | Інша фізична машина не перевірялася |

Репозиторій публічний, тому умова AC10 щодо credentials для приватного Git
не застосовується. Фінальний [walkthrough](docs/walkthrough.md) містить
сценарій перевірки всіх критеріїв і межі перевірки AC9.
