# Development Plans

Робочі плани розробки для кожного Bounded Context'а Aperitivo. На відміну від
[`docs/`](../) — де архітектурні рішення, доменні моделі, ADR-и — ця папка тримає
**операційні плани**: що читати, в якому порядку, які кроки реалізовувати, які
залежності між кроками.

## Формат плану

Кожен план — окремий файл `<bc-name>.md`. Структура плану:

1. **Prerequisites reading** — документи, які треба прочитати перед стартом (наш проєкт + зовнішні джерела).
2. **Practical work breakdown** — кроки реалізації в правильному порядку залежностей.
3. **Progress tracking** — чекбокси, які закриваються по мірі виконання.

Плани — living documents. Після завершення BC файл лишається як історичний запис "як воно робилося".

## Available plans

| BC | Plan | Status |
|---|---|---|
| Identity & Access | [identity.md](identity.md) | In progress |
| Activity Ingestion | *(not yet)* | Not started |
| Workout Catalog | *(not yet)* | Not started |
| Performance Analytics | *(not yet)* | Not started |
| Training Planning | *(not yet)* | Not started |
| Notifications | *(not yet)* | Not started |

## Related

- [Roadmap](../business/roadmap.md) — стратегічний порядок фаз (phase-by-phase)
- [Bounded Contexts](../architecture/bounded-contexts.md) — концептуальна декомпозиція
- ADRs в [`docs/adr/`](../adr/) — обґрунтування архітектурних рішень
