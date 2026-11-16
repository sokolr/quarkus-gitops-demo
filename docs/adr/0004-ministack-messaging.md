# ADR 0004: MiniStack для локальних SNS/SQS

## Рішення

- Використовуємо MiniStack `1.5.39` з MIT-ліцензією. Він підтримує потрібні
  операції SNS/SQS, fanout, raw delivery і DLQ; це перевіряє контрактний Job
  під час bootstrap. Версію фіксуємо, бо проєкт швидко змінюється.
- Розгортаємо MiniStack окремим StatefulSet з PVC і явними readiness/liveness
  probes. У кластері endpoint — `http://ministack:4566`, регіон `us-east-1`.
- Topic, дві черги, redrive policy, SQS policy і підписку створює ідемпотентний
  ArgoCD PostSync Job. Його можна повторити після зміни конфігурації; bootstrap
  не запускає сервіси, доки перевірка SNS/SQS не завершиться успішно.

## Наслідки

MiniStack є новішим проєктом, ніж AWS, тому локальний успіх не заміняє
перевірку IAM, queue policy і довговічності в реальному AWS. Його persistence
робить знімок стану під час завершення процесу; PVC не усуває ризик втрати
останніх повідомлень при аварійному завершенні. На наступному кроці outbox у
PostgreSQL захищатиме від втрати наміру опублікувати подію під час недоступності
емулятора.

Джерела: [ліцензія та релізи](https://github.com/ministackorg/ministack),
[SNS](https://www.ministack.org/docs/services/sns),
[SQS](https://ministack.org/docs/services/sqs),
[збереження стану](https://ministack.org/docs/configuration).
