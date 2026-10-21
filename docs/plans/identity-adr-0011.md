# Work Plan: завершення міграції на ADR 0011 (Google login + connect on demand)

Фокусований під-план [`identity.md`](identity.md) — доводить до кінця редизайн, запущений
[ADR 0011](../adr/0011-identity-provider-signup.md): логін через Google (OIDC), Strava
підключається окремо як data source.

**Область:** тільки те, що лишилось між поточним станом коду і тим, що вже описано в
`docs/`. Механіка `TokenManager` / AES-GCM / refresh / revocation — **не чіпається**.

**Як користуватись:** кроки йдуть у порядку залежностей. Для кожного — блок
**📖 Читати перед кроком** (спершу наш `docs/`, потім зовнішнє) і **✅ Definition of done**.
Читай саме перед кроком, а не все одразу: більшість дефектів нижче — це розбіжність між
кодом і документом, який уже написаний.

**Батьківський план:** [identity.md](identity.md) — кроки нижче деталізують B.2, B.6, B.7,
B.8, B.13 і додають те, чого в них не було.

---

## Стан на старті плану

Перевірено на робочому дереві:

| | Стан |
|---|---|
| `./mvnw compile` | ✅ проходить |
| Інтеграційні тести | ❌ **не стартують** — контекст падає на Flyway (крок 1) |
| `ApplicationModules.verify()` | ⚠️ не виконується — блокований тим самим падінням |
| Doc follow-ups з ADR 0011 | ✅ закриті (glossary, use-cases, naming, copilot-instructions, ADR 0004/0009 amended) |
| Google login end-to-end | ⚠️ частково — `User`+`UserIdentity` пишуться, але JWT не мінтиться |
| Strava connect end-to-end | ❌ `oauth2Client` сконфігурований, але нічого не персистить |

Зроблено і **не** потребує переробки: `IdentityProvider`/`DataProvider` як два різні енами;
`loginAuthorizationRequestResolver`, що звужує `oauth2Login` до однієї реєстрації;
`redirect-uri: {baseUrl}/connect/oauth2/code/{registrationId}` для Strava;
`user_identities` без токенів і без status-lifecycle.

---

## Порядок і залежності

```
1. Flyway V4 collision ─┬─> 2. Констрейнти + lastLoginAt ──> 4. isNewUser routing
                        │
                        └─> 3. JWT + resource server ──────> 5. Connect-персистенс ──> 6. Події
                                                                                        │
                                                              7. Чистка + anti-drift <──┘
```

Кроки 1–2 розблоковують `ApplicationModules.verify()` і взагалі будь-який IT — без них
решта робиться наосліп.

---

## Крок 1 — Розвести Flyway-версії (блокер)

**Симптом (відтворено):**

```
FlywayException: Found more than one migration with version 4
-> target/test-classes/db/migration/identity/V4__create_event_publication_table_for_itest.sql
-> target/classes/db/migration/identity/V4__create_user_identities_table.sql
```

`application-itest.yaml` задає `locations: classpath:db/migration/identity`, а
`build-helper` кладе test-resources у той самий classpath-шлях. Продуктова `V4` зіткнулася
з тестовим дзеркалом `event_publication`. Контекст не піднімається → падають **усі** IT,
включно з `AperitivoApplicationTests` (`ApplicationModules.verify()`).

### 📖 Читати перед кроком

- [`database.md` §Migrations](../contexts/identity-access/database.md) — правило «один changeset per BC», поточна послідовність V1–V4.
- Хедер-коментар у `src/test-integration/resources/db/migration/identity/V4__create_event_publication_table_for_itest.sql` — пояснює, **чому** тестове дзеркало взагалі існує (Modulith `__root` не запускається під `flyway-enabled: false`).
- `src/test-integration/resources/application-itest.yaml` — `spring.flyway.locations` і `spring.modulith.runtime.flyway-enabled`.
- [Spring Modulith — Events (JDBC schema initialization)](https://docs.spring.io/spring-modulith/reference/events.html) — звідки береться `event_publication` і чому в проді її створює інший шлях.
- [Flyway — Migrations](https://documentation.red-gate.com/fd) — версіонування, порядок, чому дублікат версії фатальний.

### Кроки

- [ ] Винести test-only дзеркало у діапазон, який продуктові міграції ніколи не займуть (напр. `V9001__`), щоб наступна продуктова `V5`/`V6` не влаштувала те саме вдруге.
- [ ] Зафіксувати це правило одним реченням у [`database.md` §Migrations](../contexts/identity-access/database.md) — інакше граблі повернуться на `V5`.
- [ ] Перезапустити один IT і переконатися, що контекст піднімається.

### ✅ Definition of done

`./mvnw verify -Dit.test=EncryptedBytesConverterIT` проходить; `AperitivoApplicationTests`
(`ApplicationModules.verify()`) зелений.

---

## Крок 2 — Довести `V4` до схеми з `database.md`

Спершу міграція створювала таблицю **без жодного `UNIQUE`**, хоча `database.md` їх описує.
Це не косметика: `handleLogin` робить read-then-insert без захисту, тож два паралельні перші
логіни того самого Google-акаунта створять **два `UserEntity`** — рівно той дубль, проти
якого написана ADR 0011. Обидва `UNIQUE` уже додано в `V4`; решта — у чеклісті нижче.

| Документовано в `database.md` | Є в `V4` |
|---|---|
| `uq_user_identities_provider_user_id_provider (provider_user_id, provider)` | ✅ |
| `uq_user_identities_user_id_provider (user_id, provider)` | ✅ |
| `last_login_at TIMESTAMPTZ NULL` | ✅ |
| `updated_at` — **відсутній** у доці | ⚠️ є в міграції та в entity |

Окремий `ix_user_identities_user (user_id)` **не потрібен**: `user_id` — провідна колонка
`uq_user_identities_user_id_provider`, яка обслуговує і пошук за `user_id`, і FK-перевірку
при видаленні юзера (сам FK індекс не створює). Якщо цей констрейнт колись приберуть —
індекс на `user_id` треба повернути.

### 📖 Читати перед кроком

- [`database.md` §user_identities + §Constraints and indexes](../contexts/identity-access/database.md) — цільова DDL і рационале кожного індексу.
- [`domain-model.md` §UserIdentityEntity](../contexts/identity-access/domain-model.md) (рядок ~63) та §Invariants — зокрема `lastLoginAt` і правило «одна provider-identity → рівно один User».
- [`idempotency-and-outbox.md` §Consume side — three dedup patterns](../technical-notes/idempotency-and-outbox.md) — **pattern 2 (unique-constraint dedup)** — це саме те, як ловити гонку: констрейнт + `DataIntegrityViolationException` + повторний read.
- [ADR 0011 §2 і §4](../adr/0011-identity-provider-signup.md) — чому ключ завжди `(issuer, sub)` і чому конфлікт — це `409`, а не re-assign.
- [`api-errors.md`](../conventions/api-errors.md) — форма помилки, якщо конфлікт вилазить у HTTP.

### Кроки

- [x] Додати обидва unique-констрейнти у `V4` (міграція ще не застосована ніде — правити її на місці законно, окремої `V5` не треба). Окремий індекс на `user_id` не потрібен — див. примітку під таблицею вище.
- [ ] Вирішити розбіжність `last_login_at` vs `updated_at`: або міграція+entity доганяють доку, або доку виправити. Обрати **одне** і зробити консистентним у трьох місцях, де `lastLoginAt` уже описаний ([`domain-model.md`](../contexts/identity-access/domain-model.md), [`api.md`](../contexts/identity-access/api.md), [`google-login.md`](../contexts/identity-access/diagrams/sequence/google-login.md)).
- [ ] Реалізувати гілку returning-user у сервісі логіну — зараз вона не пише нічого.
- [ ] Обробити гонку first-login по pattern 2 (не покладатись на те, що «навряд чи станеться»).
- [ ] IT на конкурентний перший логін: два потоки, один `User` на виході.

### ✅ Definition of done

Констрейнти в БД; конкурентний IT зелений; `lastLoginAt` оновлюється; жодної розбіжності
між `V4`, entity і `database.md`.

---

## Крок 3 — JWT issuance + resource server

Зараз у `OAuth2GoogleLoginSuccessHandler` стоїть `//TODO issue JWT HERE`, тож логін дає
лише сесію. Наслідки: `apiChain` має `anyRequest().authenticated()` + `STATELESS` +
закоментований `oauth2ResourceServer` → `/api/**` недосяжний за жодних умов;
`/.well-known/jwks.json` у `permitAll`, але контролера немає.

**Питання, яке треба закрити саме тут:** браузерна сесія існує лише на час OAuth-редіректів,
чи вона і є автентифікація для браузера? Від відповіді залежить, чим захищений `/connect/**`
(зараз — сесією, а `api.md` описує cookie-JWT + resource server).

### 📖 Читати перед кроком

- [`jwt-and-keys.md`](../technical-notes/jwt-and-keys.md) — **головний документ кроку**: §What the token is, §Issuing — `JwtEncoder`, §Validating — resource server, §Key handling.
- [ADR 0009](../adr/0009-identity-spring-security.md) — чому self-issued RS256 і чому без зовнішнього identity server.
- [`api.md` §Authentication model](../contexts/identity-access/api.md) — «one token, two transports», атрибути cookie, `CookieOrHeaderBearerTokenResolver`, CSRF-міркування.
- [`google-login.md`](../contexts/identity-access/diagrams/sequence/google-login.md) — phase 5: що саме робить success handler і чому мінт **поза** транзакцією провіженінгу.
- [`security.md`](../operations/security.md) — зберігання ключів, секрети.
- [Spring Security — JWT / JOSE](https://docs.spring.io/spring-security/reference/servlet/oauth2/jwt.html) — `JwtEncoder`, `NimbusJwtEncoder`, `JWKSource`.
- [Spring Security — OAuth 2.0 Resource Server / JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) — `JwtDecoder`, `BearerTokenResolver`, `JwtAuthenticationConverter`.

### Кроки

- [ ] RSA keypair з конфігу (env-driven, як `AES_GCM_SECRET`), `JwtEncoder` bean.
- [ ] Claims рівно ті, що в ADR 0009: `sub` = internal `user_id`, `iss`, `iat`, `exp`, `jti`. **Ніяких** Strava-токенів і **не** Google `sub`.
- [ ] Мінт у success handler після `handleLogin`, cookie `HttpOnly; Secure; SameSite=Lax`.
- [ ] `@RestController` для `/.well-known/jwks.json` (зараз шлях у `permitAll`, але 404).
- [ ] Розкоментувати й дописати `oauth2ResourceServer` в `apiChain` + `BearerTokenResolver`, що читає header, потім cookie.
- [ ] Визначитись із `/connect/**`: який чейн його матчить і чим він автентифікований.
- [ ] Перший захищений endpoint `GET /api/me` як smoke-перевірка.

### ✅ Definition of done

Логін кладе JWT у cookie; `GET /api/me` віддає дані під тим самим токеном і в header-, і в
cookie-формі; JWKS віддає публічний ключ.

---

## Крок 4 — `isNewUser` веде на обов'язковий connect

`handleLogin` повертає `LoginResult`, і значення **викидається**. Тобто обов'язкового
connect-кроку з ADR 0011 §5 не існує: щойно зареєстрований юзер потрапляє на порожній
дашборд замість `/connect/strava`.

Найдешевший крок у списку — і єдиний, що робить різницю видимою користувачу.

### 📖 Читати перед кроком

- [ADR 0011 §5](../adr/0011-identity-provider-signup.md) — «Onboarding has an explicit connect step» і чому це стадія онбордингу, а не кнопка на дашборді.
- [`use-cases.md`](../business/use-cases.md) — **UC-1** (sign-up, порожній акаунт) і **UC-1b** (connect Strava). Вони розділені саме через це.
- [`api.md`](../contexts/identity-access/api.md) — рядок про те, що `isNewUser` вирішує landing page.
- [`google-login.md`](../contexts/identity-access/diagrams/sequence/google-login.md) — phase 6 «deliberate dead end» + секція «Login variations».
- [ADR 0011 §Consequences](../adr/0011-identity-provider-signup.md) — «funnel cost is real»: чому цей редірект компенсує головний мінус топології.

### Кроки

- [ ] Використати `LoginResult.isNewUser` у success handler для вибору target URL.
- [ ] Onboarding-guard: поки в юзера нема жодного `ConnectedSource` — роутити на connect (не лише для щойно створеного; юзер міг відключити джерело).
- [ ] Врахувати, що `SavedRequestAwareAuthenticationSuccessHandler` має власну логіку saved-request — вирішити, що переважає.

### ✅ Definition of done

Новий юзер після логіну опиняється на connect-сторінці; повторний логін з активним
джерелом — на home.

---

## Крок 5 — Connect-флоу починає щось зберігати

`oauth2Client` сконфігурований і зробить code-exchange, але результат осяде в дефолтному
in-memory сторі — у `connected_sources` не потрапить нічого. Симптом, який це доводить:
`ConnectedSourceEntityMapper` і `OAuth2TokensMapper` зараз мають **нуль викликів**.

Три підпитання, які тут доведеться розв'язати:

1. **Де seam.** В `oauth2Client` немає `AuthenticationSuccessHandler` — шукай компонент, який фреймворк смикає одразу після обміну і який має метод `save…`.
2. **Чий principal.** `authentication.getName()` після Google-логіну = Google `sub`, а не наш `user_id`. Ключ має бути стабільним і не залежати від провайдера.
3. **Звідки athlete id.** `StravaUserMapper` видалено разом із диспетчером. Strava кладе об'єкт `athlete` прямо у token response — дивись `OAuth2AccessTokenResponse#getAdditionalParameters`.

Плюс: `GrantedScopeValidator` зник, а перевірка «юзер не зняв галочку `activity:read_all`»
нікуди не поділась.

### 📖 Читати перед кроком

- [`strava-connect.md`](../contexts/identity-access/diagrams/sequence/strava-connect.md) — **головна діаграма кроку**; зокрема нота про те, чому `OAuth2AuthorizationCodeGrantFilter` матчить колбек за redirect-URI реєстрації.
- [`api.md` §Two OAuth flows, two pieces of Spring Security](../contexts/identity-access/api.md) — таблиця відмінностей login vs connect, і звідки береться `user_id` (з нашого JWT, ніколи зі Strava).
- [ADR 0011 §3](../adr/0011-identity-provider-signup.md) — чому саме `oauth2Client` і чому він не чіпає `SecurityContext`.
- [`domain-model.md` §ConnectedSourceService](../contexts/identity-access/domain-model.md) — контракт `linkSource`, реактивація vs новий рядок.
- [`database.md` §What reconnect actually does](../contexts/identity-access/database.md) — таблиця трьох сценаріїв реконекту; пояснює, чому «просто вставити новий рядок» неправильно в половині випадків.
- [ADR 0003](../adr/0003-token-storage-strategy.md) + [`encryption-at-rest.md`](../technical-notes/encryption-at-rest.md) — токени в `connected_sources` шифруються, шлях уже існує.
- [`token-management.md`](../technical-notes/token-management.md) — щоб не продублювати те, що вже вміє `TokenManager`.
- [`oauth-flow.md`](../integrations/strava/oauth-flow.md) — наш опис Strava-флоу (переписаний як **connect**, не login).
- [`api-errors.md`](../conventions/api-errors.md) — форма `409 …/errors/source-already-linked`.
- [Spring Security — Authorization Grant Support](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html) — `OAuth2AuthorizationCodeGrantFilter`, поведінка на колбеку.
- [Spring Security — OAuth2 Client Core Interfaces](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/core.html) — `OAuth2AuthorizedClientRepository` / `…Service`, їхні `save…` методи та ключування по principal.
- [Strava — Authentication](https://developers.strava.com/docs/authentication/) — що саме приходить у token response (включно з `athlete`) і як виглядає `scope` у колбеку.

### Кроки

- [ ] Вибрати і реалізувати seam, який персистить результат code-exchange у `connected_sources`.
- [ ] Взяти `userId` з нашої автентифікації, не з відповіді Strava.
- [ ] Дістати athlete id → `providerUserId`.
- [ ] Повернути перевірку granted scopes (юзер може зняти `activity:read_all`).
- [ ] Реалізувати реактивацію-на-місці згідно з таблицею реконекту, а не сліпий insert.
- [ ] Конфлікт «джерело вже прив'язане до іншого User» → `409` з Problem Details.
- [ ] `GET /api/me/sources`, `DELETE /api/me/sources/{provider}` (→ `markRevoked`, `USER_REQUESTED`).
- [ ] IT: connect happy path, реконект після revoke, конфлікт, відмова в scope.

### ✅ Definition of done

Автентифікований юзер проходить `/connect/strava` і отримує `ACTIVE` рядок із зашифрованими
токенами; `TokenManager.getValidAccessToken(userId)` після цього працює.

---

## Крок 6 — Привести події до задокументованих контрактів

Обидві події лишились заглушками `(UUID id)`:

| Код зараз | `events.md` |
|---|---|
| `UserRegisteredEvent(UUID id)` | `userId` + `occurredAt` |
| `IntegrationConnectedEvent(UUID id)` | `userId`, `connectedSourceId`, `provider`, `occurredAt` |

Для другої це вже критично: після ADR 0011 вона — **єдиний** тригер backfill в Ingestion.

### 📖 Читати перед кроком

- [`events.md`](../contexts/identity-access/events.md) — **авторитетне джерело контрактів** цього BC; зокрема нота, чому `connectedSourceId` **не** є per-occurrence dedup-ключем (реконект переюзає той самий рядок).
- [`events/conventions.md`](../events/conventions.md) — past-tense, `…Event`-суфікс, `user_id` у кожному payload, версіонування.
- [`events/catalog.md`](../events/catalog.md) — крос-BC індекс; оновлюється **після** per-BC файлу, не замість.
- [`idempotency-and-outbox.md`](../technical-notes/idempotency-and-outbox.md) — at-least-once, §Checklist for a new consumer, вибір pattern для споживача.
- [ADR 0008](../adr/0008-event-transport.md) — чому Modulith in-process, а не брокер.
- [`initial-backfill.md`](../contexts/activity-ingestion/diagrams/sequence/initial-backfill.md) — як Ingestion споживає `IntegrationConnected` і як розрізняє перший connect vs реконект.

### Кроки

- [ ] Привести обидва record-и до контрактів з `events.md` (поля й імена, `occurredAt` з інжектованого `Clock`).
- [ ] Перевірити, що `IntegrationRevokedEvent` (уже повний) лишився консистентним.
- [ ] Оновити [`events/catalog.md`](../events/catalog.md), якщо щось розійшлося.
- [ ] Тест, що подія публікується рівно на переході в `ACTIVE`, а не на кожному connect.

### ✅ Definition of done

Record-и збігаються з `events.md` поле-в-поле; тест на публікацію зелений.

---

## Крок 7 — Чистка і anti-drift тест

Дрібне, що лишилось, і одна річ, яку вимагає сама ADR.

**Знайдене:**

- `OAuth2ClientController` — хардкодить `"strava"`, гарантований NPE на `/` під `permitAll` (anonymous principal → `loadAuthorizedClient` повертає `null`).
- `requireProfile` кидає `OAuth2AuthenticationException` із **success** handler-а, куди failure handler уже не дотягнеться → 500 замість редіректу на `/login?error=`.
- `oidcUser.getAttribute("sub"/"email"/…)` замість типізованих `getSubject()`, `getEmail()`, `getFullName()`, `getPicture()`.
- Сервіс називається `OAuth2GoogleLoginCallbackService`, а `domain-model.md` описує `LoginService` — розійтись у назвах на старті дешево, потім ні.
- `email_verified` з ID-токена ніде не перевіряється.

### 📖 Читати перед кроком

- [ADR 0011 §Risks](../adr/0011-identity-provider-signup.md) — прямо приписує **інтеграційний тест**: login-chain віддає рівно identity-реєстрації, жоден data-source registration не досяжний з login base-URI.
- [`domain-model.md` §Services](../contexts/identity-access/domain-model.md) — канонічні імена сервісів і межі їхньої відповідальності.
- [`naming.md`](../conventions/naming.md) — суфікси, і окремо правило про два різні `sub`.
- [`spring-modulith-boundaries.md`](../technical-notes/spring-modulith-boundaries.md) — що має бути в base package (API), а що в приватному; `ApplicationModules.verify()` у CI.
- [`api.md` §`GET /api/me/identities`](../contexts/identity-access/api.md) — інваріант «не можна видалити останній sign-in метод».
- [Google — OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect) — які клейми гарантовані, що робити з `email_verified`.

### Кроки

- [ ] Викинути або переписати `OAuth2ClientController` (це залишок debug-коду часів Strava-логіну).
- [ ] Помилку в success handler віддавати так, щоб юзер бачив `/login?error=`, а не 500.
- [ ] Узгодити імена сервісів із `domain-model.md` (або оновити доку, якщо код правіший).
- [ ] Anti-drift IT з ADR §Risks.
- [ ] `GET /api/me/identities`, `DELETE /api/me/identities/{provider}` + інваріант останнього методу входу, покритий тестом.
- [ ] Переконатися, що `ApplicationModules.verify()` лишився зеленим після всіх переміщень.

### ✅ Definition of done

`ApplicationModules.verify()` зелений; anti-drift тест падає, якщо хтось додасть Strava в
`oauth2Login`; жодного мертвого коду з попередньої топології.

---

## Наскрізний чек-лист

- [ ] Крок 1 — Flyway V4 розведено, IT стартують
- [ ] Крок 2 — констрейнти + `lastLoginAt`, гонка first-login закрита
- [ ] Крок 3 — JWT мінтиться, resource server увімкнено
- [ ] Крок 4 — `isNewUser` веде на connect
- [ ] Крок 5 — connect пише `connected_sources`
- [ ] Крок 6 — події відповідають `events.md`
- [ ] Крок 7 — чистка + anti-drift тест

Після закриття всіх семи: оновити чекбокси B.6 / B.7 / B.8 / B.13 у [`identity.md`](identity.md)
і статус [Phase 1](../business/roadmap.md#phase-1--identity--access) у roadmap.

---

## Related

- [ADR 0011](../adr/0011-identity-provider-signup.md) — керівне рішення цього плану
- [ADR 0010 (superseded)](../adr/0010-single-canonical-login-provider-superseded.md) — що саме змінилось і що вціліло
- [identity.md](identity.md) — повний план IAM (Phase A reading, B.1–B.13, Phase C hardening)
- [`docs/contexts/identity-access/`](../contexts/identity-access/README.md) — BC deep dive
