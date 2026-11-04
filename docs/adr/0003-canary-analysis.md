# ADR 0003: автоматичний аналіз canary

## Рішення

- Локальний Prometheus розгортається окремою ArgoCD Application у namespace
  `gitops-demo`. Він має тільки namespace-scoped RBAC на виявлення Pod і збирає
  злиті Istio metrics із Service A/B через анотації, додані Istio sidecar.
  Дані Prometheus зберігаються в `emptyDir` 6 годин: для демо це достатньо,
  але після перезапуску Pod історія метрик губиться. Kustomize додає hash до
  імені ConfigMap, тому зміна конфігурації запускає новий Prometheus Pod.
- `AnalysisTemplate` використовує `istio_requests_total`, `reporter=destination`
  та hash нового ReplicaSet, переданий Rollouts в AnalysisRun. Prometheus додає
  цей hash із label Pod під час scrape. Це вимірює результати запитів
  до нової версії окремо від stable. Успіх — три послідовні виміри з часткою
  відповідей без 5xx не нижче 95%; один вимір нижче порога — abort.
  Відсутність traffic повертає 0, тож порожні метрики не дадуть promotion.
- Постійний невеликий потік запитів іде через Istio VirtualService A → B, щоб
  метрика canary мала спостереження при 10% ваги. Він і сам Prometheus є
  інфраструктурою демо, а не частиною бізнес-логіки застосунків.
- Помилковий canary запускається конфігурацією `DEMO_FAULT_PERCENT=100` тільки
  для нових Pod Service B. Readiness лишається здоровою, отже Rollouts доходить
  до аналізу, бачить 503 та автоматично повертає трафік stable.

## Наслідки

Якщо Prometheus недоступний або немає успішних вимірів, promotion не слід
виконувати вручну: треба перевірити `AnalysisRun`. При abort stable продовжує
обслуговувати трафік, а Rollout та ArgoCD Application залишаються `Degraded`,
доки бажану конфігурацію не повернуто до stable в Git. Поточний Prometheus
призначений для одного локального кластера, без HA та довгого зберігання.
