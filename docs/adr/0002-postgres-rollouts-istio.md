# ADR 0002: PostgreSQL, Secrets та canary-трафік

## Рішення

- Для локального середовища PostgreSQL 18.6 запускається як один StatefulSet із
  PVC через стандартний StorageClass Minikube. PostgreSQL і PVC описані в Git та
  синхронізуються ArgoCD. Оператор БД і HA не потрібні для цього демо; видалення
  Minikube-профілю видаляє й дані.
- `make up` створює Kubernetes Secret `service-b-db` у кластері до запуску
  PostgreSQL та Service B. Пароль генерується випадково й не потрапляє до Git.
  Secret не є зовнішнім vault і сам по собі не гарантує шифрування etcd; для
  production знадобилися б окреме управління ключами й політики доступу.
- Flyway виконує міграції під час старту Service B. Для короткої транзакційної
  міграції V2 це просто та дозволяє першому V2 Pod розширити схему до роботи з
  трафіком. Flyway координує конкурентні старти через блокування БД. Старі Pod
  з V1 SQL продовжують працювати після `ADD COLUMN`, зокрема після rollback.
- Обидва Deployment замінено на Rollout із canary 10%, 50%, 100% і аналізом
  після 10% та 50%.
  Argo Rollouts перемикає ваги Istio VirtualService між stable/canary Service.
  Gateway дає вхід до Service A; A → B маршрутизується в mesh. Це забезпечує
  ваги трафіку незалежно від співвідношення кількості Pod.
- ArgoCD не порівнює ваги VirtualService й `rollouts-pod-template-hash` у
  selector stable/canary Service, бо ці поля змінює Argo Rollouts. `PruneLast`
  залишає старі Deployment до готовності нових ресурсів під час переходу.

## Наслідки

Для B максимальна кількість Pod під час canary становить 3: 2 базові + 1 surge.
Пул обмежено 5 з'єднаннями на Pod, тобто максимум 15 одночасних з'єднань
застосунку до PostgreSQL, не враховуючи короткі адміністративні з'єднання.
За замовчуванням PostgreSQL дозволяє більше, але ліміт слід перевірити при
зміні розміру пулу або кількості реплік.

Прямий port-forward до Service/Pod обходить Istio і не показує canary-розподіл.
Для перевірки ваг використовується Istio ingress gateway. Автоматичний
аналіз Prometheus і abort несправного canary описані в ADR 0003.
