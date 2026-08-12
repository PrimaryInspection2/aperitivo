# Development Plan: Identity & Access

Робочий план розробки IAM модуля — першого BC в roadmap. Слідуй порядку: спершу
прочитай, потім плануй код, потім реалізовуй з assistant-review.

**Related roadmap phase:** [Phase 1 — Identity & Access](../business/roadmap.md#phase-1--identity--access)

**Related BC deep dive:** [`docs/contexts/identity-access/`](../contexts/identity-access/README.md)

> **Активний під-план:** [identity-adr-0011.md](identity-adr-0011.md) — покрокове завершення
> міграції на [ADR 0011](../adr/0011-identity-provider-signup.md) (Google login + connect on
> demand), з переліком доків для читання на кожен крок. Деталізує B.6, B.7, B.8, B.13 нижче
> і додає те, чого в них не було. Поки він не закритий — працюй по ньому, а чекбокси тут
> оновлюй за фактом.

---

## Phase A — Reading (концептуальний фундамент)

Читай саме в цій послідовності, щоб контекст будувався пошарово: спочатку **чому** (ADR-и), потім **що** (BC deep-dive), потім **як** (technical notes).

### A.1 — Ubiquitous language + core decisions

- [x] **[`docs/business/glossary.md`](../business/glossary.md)** — секція "Identity & Access". Терміни `User`, `ConnectedSource`, `Provider`, `IdentityProvider`, `TokenManager`, `Revocation`, `JWT`.
- [x] **[ADR 0005 — Bounded contexts](../adr/0005-bounded-contexts.md)** — чому взагалі 6 BCs, чому IAM — окремий.
- [x] **[ADR 0004 — User model & ConnectedSource](../adr/0004-user-model-and-connected-sources.md)** — чому внутрішній UUID замість `strava_athlete_id`, чому `ConnectedSource` окрема сутність.
- [x] **[ADR 0009 — Identity via Spring Security](../adr/0009-identity-spring-security.md)** — головна ADR для цього модуля.
- [x] **[ADR 0003 — Token storage strategy](../adr/0003-token-storage-strategy.md)** — чому AES-GCM у нашій БД, а не Vault/Redis.
- [x] **[ADR 0002 (superseded)](../adr/0002-identity-model-superseded.md)** — прочитати як контекст, зрозуміти чому Keycloak відкинули.
- [ ] **[ADR 0011 — Identity-provider signup (Google); sources connected on demand](../adr/0011-identity-provider-signup.md)** — **керівна ADR редизайну**: логін тепер Google (OIDC), Strava — data source, що *підключається*. Вводить `UserIdentity` / `user_identities`, connect-flow (`oauth2Client`), `/api/me/sources`. Читати перед B.6/B.13.
- [ ] **[ADR 0010 (superseded)](../adr/0010-single-canonical-login-provider-superseded.md)** — контекст: чому «Strava як єдиний login-провайдер» відкинуто на користь 0011 (лишились split login/link flow + «identity ніколи не виводиться неявно»).

### A.2 — IAM BC deep dive

- [x] **[`contexts/identity-access/README.md`](../contexts/identity-access/README.md)** — огляд.
- [x] **[`contexts/identity-access/domain-model.md`](../contexts/identity-access/domain-model.md)** — `User`, `ConnectedSource`, стани, value objects. Головний файл моделі.
- [x] **[`contexts/identity-access/database.md`](../contexts/identity-access/database.md)** — схема, індекси, partial unique index.
- [x] **[`contexts/identity-access/events.md`](../contexts/identity-access/events.md)** — `UserRegistered`, `IntegrationConnected`, `IntegrationRevoked` контракти.
- [x] **[`contexts/identity-access/api.md`](../contexts/identity-access/api.md)** — REST endpoints, OAuth flow endpoints.
- [x] **[Sequence: Google login](../contexts/identity-access/diagrams/sequence/google-login.md)** + **[Sequence: Strava connect](../contexts/identity-access/diagrams/sequence/strava-connect.md)** — читати разом з `api.md`. ADR 0011 розділив старий `strava-oauth-login` на два потоки: login (Google) vs connect (Strava).
- [x] **[Sequence: Token refresh](../contexts/identity-access/diagrams/sequence/token-refresh.md)** — механіка refresh з concurrency-lock.
- [x] **[Sequence: Revocation flow](../contexts/identity-access/diagrams/sequence/revocation-flow.md)** — 3 шляхи revocation.

### A.3 — Technical deep dives

- [x] **[`technical-notes/token-management.md`](../technical-notes/token-management.md)** — **найважливіший**. `TokenManager`, AES-GCM, per-user lock (`pg_advisory_xact_lock` vs Redis `SET NX PX`), lazy vs proactive refresh, revocation triggers.
- [x] **[`technical-notes/jwt-and-keys.md`](../technical-notes/jwt-and-keys.md)** — `JwtEncoder` RS256, JWKS endpoint, `jti` blacklist у Redis.
- [x] **[`technical-notes/encryption-at-rest.md`](../technical-notes/encryption-at-rest.md)** — AES-GCM column encryption, nonce management, envelope encryption path.
- [x] **[`technical-notes/spring-modulith-boundaries.md`](../technical-notes/spring-modulith-boundaries.md)** — як `internal/` package працює, як `TokenManager` — published port у base package.

### A.4 — Cross-cutting conventions

- [x] **[`conventions/naming.md`](../conventions/naming.md)** — суфікси класів (`Entity`, `Dto`, `Event`, `Service`, `Repository`), винятки (`TokenManager`).
- [x] **[`conventions/api-errors.md`](../conventions/api-errors.md)** — RFC 7807 Problem Details.

### A.5 — Use cases + ops контекст

- [x] **[`business/use-cases.md`](../business/use-cases.md)** — UC-1 (first sign-up), UC-2 (returning login), UC-4 (revocation).
- [x] **[`operations/security.md`](../operations/security.md)** — threat model, secrets management, audit logging.
- [x] **[`operations/runbooks/token-refresh-failures.md`](../operations/runbooks/token-refresh-failures.md)** — failure modes для refresh.
- [x] **[`operations/runbooks/deauthorization-storm.md`](../operations/runbooks/deauthorization-storm.md)** — коли багато revocation одночасно.

### A.6 — Strava external docs

- [x] **[`integrations/strava/oauth-flow.md`](../integrations/strava/oauth-flow.md)** — наш опис Strava OAuth.
- [x] **[`integrations/strava/api-quirks.md`](../integrations/strava/api-quirks.md)** — rate limits, headers, недокументовані нюанси.
- [x] **[`integrations/strava/webhooks.md`](../integrations/strava/webhooks.md)** — deauth webhook (стосується IAM).
- [x] **[Strava API Getting Started](https://developers.strava.com/docs/getting-started/)** — базовий огляд.
- [x] **[Strava Authentication](https://developers.strava.com/docs/authentication/)** — офіційний опис OAuth 2.0 flow (не OIDC — важлива відмінність).
- [x] **[Strava Webhooks](https://developers.strava.com/docs/webhooks/)** — subscription + delivery + deauth.
- [x] **[Strava API Reference](https://developers.strava.com/docs/reference/)** — `/oauth/authorize`, `/oauth/token`, `/oauth/revoke` (legacy `/oauth/deauthorize` — до 2027-06-01; станом на 2026-08-07 `/oauth/revoke` віддає `503`, див. [oauth-flow.md](../integrations/strava/oauth-flow.md)).
- [x] **[Strava Rate Limits](https://developers.strava.com/docs/rate-limits/)** — 100 req / 15 min, 1000 req / day.

### A.7 — Spring / Modulith external docs

- [x] **[Spring Security — OAuth 2.0 Client](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html)** — вся секція. `ClientRegistration`, `oauth2Login()`, `OAuth2UserService`, `OAuth2AuthorizedClientService`.
- [x] **[Spring Security — OAuth 2.0 Resource Server / JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)** — `JwtDecoder`, `JwtAuthenticationConverter`.
- [x] **[Spring Security — JWT / JOSE / JwtEncoder](https://docs.spring.io/spring-security/reference/servlet/oauth2/jwt.html)** — як мінтити свій JWT.
- [x] **[Spring Modulith — Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)** — `@ApplicationModule`, `internal/` конвенція.
- [x] **[Spring Modulith — Events](https://docs.spring.io/spring-modulith/reference/events.html)** — publishing, outbox, `event_publication`.
- [x] **[Flyway Documentation](https://documentation.red-gate.com/fd)** — конвенції міграцій.

---

## Phase B — Practical work breakdown

Кожен пункт — окрема ітерація. Assistant не пише код за тебе — він рев'ює, кидає виклик рішенням, підказує, коли треба.

### B.1 — Module skeleton
- [x] Створити пакети `dev.amishko.aperitivo.identity` (base = API) + `dev.amishko.aperitivo.identity` (private).
- [x] Створити `package-info.java` з `@ApplicationModule` метаданими (опційно).
- [x] Написати `ModularityTests` з `ApplicationModules.of(...).verify()`.
- [x] Переконатися, що `verify()` проходить (порожній модуль — теж валідний).
- [x] **BLOCKER:** без бізнес-коду ще. Спочатку структура + verify.

### B.2 — DB schema + first Flyway migration
- [x] Створити schema `identity` у Postgres (Flyway migration `V1__create_identity_schema.sql`).
- [x] Таблиця `users` — див. [database.md](../contexts/identity-access/database.md).
- [x] Таблиця `connected_sources` з `access_token_encrypted`/`refresh_token_encrypted` як `bytea`.
- [x] Partial unique index на `(user_id, provider) WHERE status = 'ACTIVE'` — див. [database.md](../contexts/identity-access/database.md).
- [x] Індекси для типових запитів (за `provider_user_id`, за `status`).

### B.3 — Entities + repositories (data-only)
- [x] `UserEntity` — plain data, JPA/audit annotations only.
- [x] `ConnectedSourceEntity` — plain data, enum `SourceStatus`, `Provider`.
- [x] `UserRepository`, `ConnectedSourceRepository` — Spring Data JPA.
- [x] Cross-aggregate refs — тільки `UUID`-поля, **без** `@ManyToOne` між `User` і `ConnectedSource` (per ADR 0004).

### B.4 — AES-GCM AttributeConverter
- [x] `EncryptedBytesConverter implements AttributeConverter<String, byte[]>` (у `identity/util/cipher/`).
- [x] Random 96-bit nonce per encryption (`AesGcmCipher`).
- [x] Key з `application.properties` через `EncryptionProperties` (env-driven).
- [x] Тести — `AesGcmCipherTest`, `EncryptedBytesConverterTest`, `EncryptedBytesConverterIT`.
- [x] **Не логувати** розшифровані значення — `@ToString.Exclude` на token-полях у `ConnectedSourceEntity`.

### B.5 — TokenManager (core component)
- [x] Публічний інтерфейс `TokenManager` у `dev.amishko.aperitivo.identity` base package.
- [x] `getValidAccessToken(userId)` — hot path з lazy refresh. **Пропагує transport-level exception назовні без прихованого revoke** (див. C.2).
- [x] Concurrent refresh guard — Redis lock через Redisson (`PerUserTokenRefreshLock`).
- [x] `markRevoked(userId, provider, reason)` — set status, emit `IntegrationRevokedEvent` (див. C.7 щодо `provider`-параметра).
- [x] `recordSuccessfulUse(userId)`, `recordFailure(userId, kind)` — audit trail (див. C.1 щодо семантики).

### B.5b — UserContactReader (другий published port IAM)

> Закриває діру, знайдену при рев'ю: Notifications потрібна email-адреса для SMTP, але легального
> крос-BC каналу до `users.email` не існувало (жодна подія її не несе, порту не було, а
> `notifications/domain-model.md` прямо забороняє джойнити таблиці IAM).

- [ ] Інтерфейс `UserContactReader` у `dev.amishko.aperitivo.identity` base package —
      `Optional<String> emailFor(UUID userId)`. Read-only, `Optional` замість `null`.
- [ ] Реалізація в `internal/` поверх `UserRepository` (проєкція на одну колонку, не весь entity).
- [ ] **Не** класти адресу в payload подій: Modulith серіалізує payload у `event_publication`,
      тож адреса durably копіювалась би в outbox чотирьох модулів-продюсерів.
- [ ] Notifications викликає порт **двічі**: на dispatch (чи є адреса взагалі → якщо ні, EMAIL-канал
      просто пропускається) і на send (яка адреса зараз). Копія в схемі Notifications не зберігається.
- [ ] Тест `ApplicationModules.verify()` має лишатись зеленим; тест на те, що `users.email`
      не з'являється в жодному event record.

### B.6 — Spring Security OAuth2: Google login (`oauth2Login`), Strava більше не login

> **⚠️ Переписано під [ADR 0011](../adr/0011-identity-provider-signup.md).** Раніше цей крок був
> «Sign in with Strava»: Strava як login-провайдер через `oauth2Login`, а `OAuth2UserService`
> створював `User` **і** `ConnectedSource` в одній транзакції, емітячи `UserRegisteredEvent` +
> `IntegrationConnectedEvent` разом. ADR 0011 це **скасовує**: логін тепер лише Google (OIDC),
> Strava — data source, що *підключається* окремим `oauth2Client` connect-flow (див. B.13). Старі
> пункти лишені **закресленими** як запис спланованого; нові — нижче.

**Скасовано (ADR 0011) — не реалізовувати:**
- [ ] ~~`ClientRegistration` для Strava як **login**-registration (`/login/oauth2/code/strava`).~~ → тепер connect-registration `/connect/oauth2/code/strava` (B.13).
- [ ] ~~`SecurityFilterChain` з `oauth2Login()` для Strava.~~ → Strava не login; login-chain тільки для Google.
- [ ] ~~Custom `OAuth2UserService`, що резолвить/створює `User` **+ `ConnectedSource`**.~~ → login створює лише `User` (+ `UserIdentity`), без `ConnectedSource`; source створює connect-flow (B.13).
- [ ] ~~Emit `UserRegisteredEvent` (новий) + `IntegrationConnectedEvent` (завжди) з login-callback.~~ → login емітить **лише** `UserRegisteredEvent`; `IntegrationConnectedEvent` — з connect-flow (B.13).

**Google OIDC login (нове):**
- [ ] `ClientRegistration` для Google в `application.yaml` — OIDC, scope `openid,email,profile`, `redirect-uri: {baseUrl}/login/oauth2/code/google`.
- [ ] Login `SecurityFilterChain` з `oauth2Login()` — **тільки** identity-провайдери (Google). Жоден data-source registration не досяжний з login base-URI.
- [ ] `OidcUserService` (стандартний Spring) — є ID token із стабільним `sub` + `email_verified`; кастомний `OAuth2UserService` для логіну **не потрібен** (на відміну від Strava).
- [ ] `OAuth2CallbackService.login(profile)` — резолвить `User` за `(provider, sub)` у `user_identities`, створює `User` + `UserIdentity` для нового; емітить `UserRegisteredEvent` тільки для нового. **Ніколи не чіпає `connected_sources`.**
- [ ] `AuthenticationSuccessHandler` — мінтить JWT (`sub` = internal `user_id`, **не** Google `sub`) і ставить cookie (див. B.7).
- [ ] Integration-тест (anti-drift, ADR 0011 «Risks»): login-chain реєструє рівно identity-провайдерів; жодного `oauth2Login` для data source.

### B.7 — JwtEncoder + JWKS endpoint
- [ ] RSA key pair loading — з config-property (base64 PEM) або файлу.
- [ ] `JwtEncoder` bean (Nimbus).
- [ ] JWT claims: `sub` = internal `user_id`, `iss`, `iat`, `exp` (24–72h), `jti`.
- [ ] `@RestController` для `/.well-known/jwks.json` (публічний ключ).
- [ ] Пізніше: key rotation з `kid` claim.

### B.8 — Resource server side (JWT validation)
- [ ] `oauth2ResourceServer().jwt()` DSL у `SecurityFilterChain`.
- [ ] `JwtDecoder` bean, що читає JWKS.
- [ ] `JwtAuthenticationConverter` — маппить `sub` → `UserId` principal.
- [ ] Перший захищений endpoint: `GET /api/me` — повертає інфо про поточного юзера.

### B.9 — Revocation flow (deauth webhook + 401 detection)
- [x] `IntegrationNotActiveException` (у `identity/exception/`) — коли `readSnapshot` бачить не-`ACTIVE` рядок.
- [x] `markRevoked` шлях у `StravaTokenManager` — set `REVOKED`, clear scopes, publish `IntegrationRevokedEvent`, state-transition guard (`if already REVOKED → noop`).
- [x] `IntegrationRevokedEvent` publish — робиться у `StravaTokenManager.markRevoked`.

### B.10 — RFC 7807 Problem Details
- [ ] `spring.mvc.problemdetails.enabled=true` у `application.properties`.
- [ ] `@ExceptionHandler` для IAM-специфічних exception-ів: `IntegrationNotActiveException`, `ConnectedSourceNotFoundException`, `OAuthBadRequestException`, `OAuthClientCredentialsException`, `OAuthTransientException`, `OAuthUnexpectedStatusException`, `LockAcquiringException`.
- [ ] `type` URIs: `/errors/integration-not-active`, `/errors/user-not-found`, etc.
- [ ] **Ніколи** не витікати токени, SQL, stack traces у body.

### B.11 — Logout + `jti` denylist у Redis
- [ ] `POST /api/auth/logout` endpoint.
- [ ] Додавати `jti` в Redis з TTL = час до `exp`.
- [ ] Кастомний `JwtDecoder` wrapper, що перевіряє denylist після валідації підпису.
- [ ] Ключ: `jti:blacklist:{jti}`, value: `1`.

### B.12 — Integration tests
- [x] Testcontainers PostgreSQL + Redis (`ContainersConfiguration`, `BaseIT`).
- [x] `StravaTokenManagerIT` — full coverage post-C.2:
  - fast path (still-valid token, REVOKED short-circuit);
  - refresh happy path;
  - **transport-exception propagation** for all three OAuth classes (`OAuthBadRequestException`, `OAuthClientCredentialsException`, `OAuthTransientException`) with explicit assertion that source stays `ACTIVE` and no `IntegrationRevokedEvent` is published (C.2 invariant);
  - concurrent double-check dedup;
  - lifecycle transitions on the inlined methods (`markRevoked`, `recordSuccessfulUse`, `recordFailure`).
- [x] `EncryptedBytesConverterIT` — encryption round-trip.
- [x] `PerUserTokenRefreshLockIT` / `PerUserTokenRefreshLockConcurrencyIT` — cross-instance lock behavior.
- [x] `StravaOAuthClientErrorDecoderTest` (unit) — status→exception mapping for 400/401/429/5xx/other-4xx/Cloudflare-522, plus body-reading resilience (null body, unreadable stream).
- [ ] `@ApplicationModuleTest` для ізольованого запуску identity модуля — не додано.
- [ ] Стуб Strava для OAuth flow (MockWebServer / WireMock) — не додано.
- [ ] Full OAuth login flow test — не додано (потребує B.6/B.7).

### B.13 — ADR 0011: `UserIdentity` + Strava connect flow

Нова робота з [ADR 0011](../adr/0011-identity-provider-signup.md): відділити **identity** (як людина
логіниться) від **data source** (звідки тягнемо дані). `user_identities` — окрема таблиця, **без
токенів**, **без status-lifecycle**; `connected_sources` лишається тільки для data sources (Strava
сьогодні, Garmin/Wahoo пізніше). Механіка `TokenManager` / AES-GCM / refresh / revocation — **без
змін**.

**DB + entity + repository + міграція:**
- [ ] Flyway міграція `user_identities` (identity schema, окремий changeset) — `user_id` FK → `users.id`; UNIQUE `(provider, provider_user_id)` = `(issuer, sub)`; **без** token-колонок, **без** `status`.
- [ ] `UserIdentityEntity` — plain data (JPA/audit), enum `IdentityProvider` (напр. `GOOGLE`); `providerUserId` = provider `sub`.
- [ ] `UserIdentityRepository` (Spring Data JPA) — `findByProviderAndProviderUserId(...)`.
- [ ] `users.email` лишається **NULLABLE** (Strava email не дає; Google дає verified email; майбутній провайдер може не дати взагалі — ADR 0011).
- [ ] **Не плутати два `sub`:** наш JWT `sub` = internal `user_id` (UUID); provider `sub` = `providerUserId` на `UserIdentity` (ADR 0011 «Risks», [naming.md](../conventions/naming.md)).

**Strava connect chain (`oauth2Client`):**
- [ ] `ClientRegistration` для Strava як **connect**-registration: `redirect-uri: {baseUrl}/connect/oauth2/code/strava`, grant `authorization_code`, scope `read,activity:read_all,profile:read_all`.
- [ ] Connect `SecurityFilterChain` з `oauth2Client` (`OAuth2AuthorizationCodeGrantFilter`) — вимагає **автентифікованого** caller-а; code-exchange **не чіпає** `SecurityContext`, `user_id` беремо з JWT.
- [ ] Явний `GET /api/v3/athlete` після code-exchange — бо `oauth2Client` (на відміну від `oauth2Login`) **не** викликає `OAuth2UserService`; звідти athlete `id` → `providerUserId` на `ConnectedSource`.
- [ ] `OAuth2CallbackService.linkSource(userId, profile, tokens)` — створює/реактивує `ConnectedSource` (AES-GCM токени, `ACTIVE`); емітить `IntegrationConnectedEvent` (**єдиний** тригер backfill в Ingestion).
- [ ] Конфлікт: provider-identity/source вже прив'язана до іншого `User` → `409 …/errors/identity-already-linked` (не re-assign, ADR 0011 §4).

**Sources API (settings):**
- [ ] `GET /api/me/sources` — підключені data sources поточного юзера.
- [ ] `DELETE /api/me/sources/{provider}` — disconnect (`markRevoked`, `USER_REQUESTED`).
- [ ] Onboarding-guard: поки немає жодного `ConnectedSource` — роутити юзера на `/connect/strava` (mandatory connect step, ADR 0011 §5).

**Identity linking (додатковий sign-in метод):**
- [ ] Лінкування другого identity-провайдера (Apple пізніше) з settings, автентифікованим юзером, тим самим link-flow — у MVP **документований seam**, не рантайм.
- [ ] **Інваріант: не можна видалити останній sign-in метод** — сервер відхиляє видалення останнього `UserIdentity` (інакше юзер втрачає доступ до акаунта). Покрити тестом.

---

## Phase C — Hardening pass (concurrency, failure-mode consistency)

Пост-B ревʼю виявило серії дефектів у `StravaTokenManager` / `StravaTokenStateService` / Feign-клієнтах і невиписаних правил класифікації exception-ів. Виправляти **разом** — це один architectural pass, а не окремі fix-и.

### C.1 — `recordFailure` / `recordSuccessfulUse` / `persistRefreshed`: counter semantics (audit-only)

**Ключове правило (з `domain-model.md` + `events.md` розділ «What is deliberately NOT an event»):** ці методи — **аудит без побічних ефектів на `status`**. Ніякого `threshold`, ніякого auto-revoke. Revoke ініціює caller напряму через `TokenManager.markRevoked(...)` для всіх трьох сценаріїв (deauth, 401 на data, refresh `400 RefreshToken invalid` — див. C.2).

- [x] **`recordSuccessfulUse(userId)`** — оновлює `lastUsedAt`, скидає `failureCount = 0`, `lastError = null`.
- [x] **`recordFailure(userId, kind)`** — оновлює `lastUsedAt`, `lastError = kind.name()`, `failureCount++`. Не змінює `status`.
- [x] **`persistRefreshed(userId, refreshed)`** — теж скидає `failureCount = 0`, `lastError = null` (успішний refresh — сильний сигнал «інтеграція жива»).
- [x] **Каскад caller-а задокументувати у Javadoc `TokenManager`** (див. секцію нижче — оновлений з урахуванням C.3 renames):

  ```
  200 OK                              → recordSuccessfulUse(userId)
  401 Unauthorized (data call)        → markRevoked(userId, TOKEN_REJECTED)
  OAuthBadRequestException            → markRevoked(userId, TOKEN_REJECTED)   [refresh 400 RefreshToken/code=invalid — dead token]
  OAuthClientCredentialsException     → NOT markRevoked. Job → system_paused, re-queued.
                                         Circuit breaker при N-th разі відкривається (C.10) → alert.
                                         Не робимо @Retryable — credentials константні, retry не поможе.
  OAuthTransientException             → recordFailure(userId, ...); retry job later
  LockAcquiringException              → retry job later (self-inflicted, не викликаємо recordFailure —
                                         це не збій інтеграції, а наш власний lock timeout)
  IntegrationNotActiveException       → source уже REVOKED; caller marks SyncJob як cancelled + логує;
                                         не викликає жодного write-методу
  429 / 5xx / net (на data call)      → recordFailure(userId, kind); retry
  ```

### C.2 — Прибрати прихований revoke із `getValidAccessToken` — DONE

**Реалізовано.** `StravaTokenManager.getValidAccessToken` не має try/catch навколо refresh: transport-level exception (`OAuthBadRequestException`, `OAuthClientCredentialsException`, `OAuthTransientException`) летить наверх до caller-а. Це відновлює CQS (read-метод без прихованих state transitions) і робить обробку симетричною для refresh-path і data-path (обидва мапляться у caller-стороні).

- [x] `StravaTokenManager.getValidAccessToken` — жодного `try/catch` навколо `refreshAccessToken`. Exception пропагується as-is.
- [x] Transport exceptions live in `identity.exception.oauth.*` **у published surface** (base package, не `internal`) — Ingestion зможе `catch`-ити напряму.
- [x] `StravaTokenManagerIT` тестує пропагацію всіх трьох класів + assert `status == ACTIVE` + assert `no IntegrationRevokedEvent` (guards from silent hidden revoke regression).
- [x] Раніше запланований проміжний domain-exception layer (`StravaRefreshRejectedException`) **не реалізовано свідомо** — caller (Ingestion) сам мапить HTTP-level exception на бізнес-дію. Менше indirection на єдиному caller-і.

### C.3 — Refresh exception классифікація — DONE (via decoder, not translation layer)

**Реалізовано.** `StravaOAuthClientErrorDecoder` мапить HTTP-статус на чотири класи transport-level exceptions напряму. Проміжний translation layer (`StravaOAuthService` з catch-and-rethrow) **свідомо викинуто** — decoder робить достатньо, і мапінг тримається як status-only, без парсингу body.

Фактичні класи, реалізовані в коді:

- [x] **`OAuthBadRequestException`** — 400. Термінальний. Означає dead credential (refresh token або auth code — залежно від того, з якого caller-flow прилетіло). Ingestion → `markRevoked(TOKEN_REJECTED)`.
- [x] **`OAuthClientCredentialsException`** — 401 на `/oauth/token`. **Terminal-config.** Наш `client_secret` / `client_id` / scope зламаний → job переходить у `system_paused`, breaker відкривається (C.10). **Не `@Retryable`** — credentials константні, retry той самий 401 дає. Fix — тільки через deploy/ops action.
- [x] **`OAuthTransientException`** — 429, 500, 502, 503, 504. Extends `TransientException`. Caller-side retry.
- [x] **`OAuthUnexpectedStatusException`** — для нестандартних 4xx/інших статусів (403, 404, 418, Cloudflare 520-527 і т.д.). Не related to revoke, не transient. Alert-worthy для дослідження.
- [x] Unit-тест `StravaOAuthClientErrorDecoderTest` покриває всі гілки + body-reading resilience.
- [ ] **`docs/technical-notes/token-management.md`** — оновити таблицю «HTTP-код → exception → дія caller-а» щоб відповідала фактичним іменам.

### C.4 — `@Retryable` уніфіковано

**Правило:** якщо entity має `@Version` і метод робить read-modify-write з **бізнес-критичним** результатом (втрата запису = проблема) → service-метод обгортається `@Retryable(ObjectOptimisticLockingFailureException.class)`.

Audit-only методи (`recordSuccessfulUse`, `recordFailure`) — `@Retryable` **не додаємо** свідомо: втрата одного оновлення `lastUsedAt` або одного інкременту `failureCount` не критична, наступний виклик компенсує. Мінус noise, мінус combo-складнощі з `@Async` (див. C.5).

- [x] Додати `spring-retry` + `@EnableRetry` (перевірити `pom.xml`).
- [x] `ConnectedSourceService.markRevoked` — `@Retryable`. Business transition, не можна втратити. *(факт: інлайнеровано в `StravaTokenManager.markRevoked` після видалення `ConnectedSourceService`; `@Retryable(ObjectOptimisticLockingFailureException.class)` на самому методі)*
- [x] `StravaTokenStateService.persistRefreshed` — `@Retryable`. Не втратити свіжий токен.
- [x] `ConnectedSourceService.recordSuccessfulUse` — **без** `@Retryable` (audit-only). *(факт: інлайнеровано в `StravaTokenManager.recordSuccessfulUse`, `@Retryable` не додано)*
- [x] `ConnectedSourceService.recordFailure` — **прибрати** `@Retryable`, який зараз стоїть за замовчуванням (audit-only, не критично). *(факт: інлайнеровано в `StravaTokenManager.recordFailure`, без `@Retryable`)*
- [x] Параметри для `markRevoked` / `persistRefreshed`: `maxAttempts = 3`, `backoff = @Backoff(delay = 50, multiplier = 2)`.
- [ ] Задокументувати правило в `docs/technical-notes/token-management.md` розділ «Concurrency policy».

### C.5 — Async fire-and-forget для audit-методів

**Комбо `@Async + @Retryable` — дозволене** але потребує акуратності. Для audit-методів `@Retryable` прибрано (C.4), тому комбо не виникає. Але для інших `@Async` beans, які додамо у майбутньому, і які матимуть `@Retryable` — тримати в голові три правила з коментарів нижче.

- [x] `recordSuccessfulUse`, `recordFailure` — `@Async` + `@Transactional` на самому service-методі. *(`@Async("iamAuditTaskExecutor")` у `StravaTokenManager`)*
- [x] Custom `TaskExecutor` bean з обмеженою чергою — не async-fork-без-меж. *(`TaskExecutorConfig.iamAuditTaskExecutor`: core=2, max=4, queue=500, `CallerRunsPolicy`)*
- [ ] `try/catch` всередині async-методу з `log.warn` — інакше exception на async-thread з'їдається void return без сліду. `@Recover` не потрібен (немає `@Retryable`).
- [x] `markRevoked` **лишається синхронним** — це не аудит, це business-transition, і caller чекає на його ефект (щоб не робити наступну спробу з тим самим токеном).

### C.6 — Transient exception base class + retry на caller-side — DONE (частково)

- [x] Базовий клас `TransientException extends RuntimeException` (у `identity.exception.retryable`) у published surface IAM. **Не marker-interface** (базовий клас з `instanceof` перевіркою у Resilience4j config).
- [x] Extend-ять: `OAuthTransientException`, `LockAcquiringException`.
- [x] `OAuthBadRequestException`, `OAuthClientCredentialsException`, `OAuthUnexpectedStatusException`, `IntegrationNotActiveException`, `ConnectedSourceNotFoundException`, `CryptoOperationException` — **не** transient, не extend.

### C.7 — `TokenManager` API consistency: прибрати `provider`

Наявна асиметрія: `markRevoked(userId, provider, reason)` бере `provider`; `getValidAccessToken(userId)` / `recordSuccessfulUse(userId)` / `recordFailure(userId, kind)` — ні. Для Strava-only MVP правильне рішення — **прибрати `provider` з `markRevoked` теж**; коли з'явиться Garmin/Wahoo, ввести `TokenManagerRegistry` або `Map<DataProvider, TokenManager>`.

- [ ] `TokenManager.markRevoked(userId, reason)` — без `provider`. Провайдер випливає з імпла (`StravaTokenManager`).
- [ ] `StravaTokenManager.markRevoked` — усередині жорстко передавати `DataProvider.STRAVA` при роботі з `ConnectedSourceRepository`.
- [ ] Оновити виклики і тести.

### C.8 — `markRevoked` observability

_(observability-only; винесено з step-plan — обробляється разом з логами/метриками)_

### C.9 — `SourceStatus.ERROR` / `markError` — прибрати з MVP

**Рішення:** прибрати `ERROR` з `ConnectedSourceStatus` enum. `markError` не реалізовуємо. Transient-стан спостерігається через `failureCount` / `lastError` / метрики. Circuit breaker (C.10) + queue-level backoff покривають глобальний transient.

Обґрунтування: `ERROR`-status в enum-і без методу, що його встановлює, — мертвий код. Дублює сигнал з `failureCount > N` без додаткової цінності. Якщо колись знадобиться (post-MVP, багато провайдерів, треба явно розрізняти нездорові) — додамо enum-значення і метод атомарно, разом.

- [ ] Видалити `ERROR` з `dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus`.
- [ ] Оновити `docs/contexts/identity-access/domain-model.md` — прибрати `SourceStatus.ERROR` і опис `markError` з описаного API IAM.
- [ ] Оновити `docs/contexts/identity-access/events.md` — прибрати згадку про `ERROR` state з розділу «What is deliberately NOT an event».
- [ ] Оновити Flyway міграцію / додати нову якщо DB constraint посилається на `ERROR` значення.

### C.10 — Resilience4j circuit-breaker на межі IAM (NEW)

**Задача, яку C.3 залишила відкритою:** `OAuthClientCredentialsException` — це системний стан, а не per-user history. Ловити його у кожного worker-а і "нічого не робити" — неправильно: 100 workerів продовжать бити Страву, генерувати шум і випалювати rate-limit. Правильне місце — **circuit breaker на межі IAM**, вище рівнем ніж Ingestion бачить.

- [ ] Додати `spring-cloud-starter-circuitbreaker-resilience4j` до `pom.xml` (перевірити, що ще не додано).
- [ ] `@CircuitBreaker(name = "strava-oauth")` на `StravaTokenManager.getValidAccessToken` (або на самому `StravaOAuthClient` bean-і — треба виміряти, де зручніше).
- [ ] `application.yaml`:
  ```yaml
  resilience4j:
    circuitbreaker:
      instances:
        strava-oauth:
          sliding-window-size: 20
          failure-rate-threshold: 50
          wait-duration-in-open-state: 60s
          record-exceptions:
            - dev.amishko.aperitivo.identity.exception.oauth.OAuthClientCredentialsException
            - dev.amishko.aperitivo.identity.exception.oauth.OAuthTransientException
          ignore-exceptions:
            - dev.amishko.aperitivo.identity.exception.oauth.OAuthBadRequestException
            - dev.amishko.aperitivo.identity.exception.terminal.IntegrationNotActiveException
  ```
- [ ] **`ignore-exceptions` — критично:** `OAuthBadRequestException` (per-user dead refresh) НЕ має відкривати global breaker. Інакше: 100 deauthorized-юзерів підряд → breaker → false storm для решти.
- [ ] Queue-level integration: SyncJob що ловить `OAuthClientCredentialsException` (або `CallNotPermittedException` від breaker) → job → `system_paused`, re-enqueued з довшим backoff. Не `@Retryable` в-процесі — це не поможе.

### C.11 — Feign per-client child-context — DONE

**Виявлений баг:** `StravaOAuthClientConfiguration` і `StravaApiClientConfiguration` були помічені `@Configuration`, тому їхні `Retryer`/`ErrorDecoder` beans потрапляли в **parent Spring context**, де два `Retryer`-и одного типу конфліктували → context load failure.

- [x] Прибрати `@Configuration` з обох config-класів. Spring Cloud OpenFeign сам створює per-client child context через `@FeignClient(configuration = X.class)` і instantiate-ить конфіг там.
- [x] Задокументувати рішення в Javadoc самих config-класів (щоб наступний хто прийде не додав `@Configuration` рефлекторно).

---

## Current status snapshot

**Done (Phase B):**
- B.1 module skeleton, B.2 DB schema, B.3 entities+repositories, B.4 AES-GCM converter, B.5 TokenManager core (окрім trace_id logging).
- B.12 partial IT: `StravaTokenManagerIT` (rewritten post-C.2), `EncryptedBytesConverterIT`, `PerUserTokenRefreshLockIT/ConcurrencyIT`, `StravaOAuthClientErrorDecoderTest`.

**Feign infrastructure для Strava OAuth — DONE:**
- Дві Feign-клієнти в окремих child-контекстах: `StravaOAuthClient` (refresh) і `StravaApiClient` (data — заготовка).
- `StravaOAuthClientErrorDecoder` — status-only mapping на 4 transport-level exceptions у published surface.
- `Retryer.NEVER_RETRY` в обох клієнтах — retry делеговано caller-стороні (Resilience4j, коли з'явиться).

**Next up (Phase B unfinished):**
- B.6 Google OIDC login (`oauth2Login` + `OidcUserService`) — **Strava-login скасовано** ([ADR 0011](../adr/0011-identity-provider-signup.md)).
- B.13 `UserIdentity` (`user_identities`) + Strava **connect** flow (`oauth2Client`) + `/api/me/sources` ([ADR 0011](../adr/0011-identity-provider-signup.md)).
- B.7 JwtEncoder + JWKS.
- B.8 Resource server (JWT validation) + `GET /api/me`.
- B.10 Problem Details for IAM controllers (blocked on B.6).
- B.11 Logout + `jti` denylist (blocked on B.7/B.8).

**Hardening pass (Phase C) — статус:**
- C.1 counter/audit semantics — ✅ reset-on-success та counter operations реалізовані у `StravaTokenManager` (після інлайну з `ConnectedSourceService`) і в `StravaTokenStateService.persistRefreshed`. Каскад caller-а задокументовано.
- ✅ C.2 прибрано прихований revoke із `getValidAccessToken` — DONE. Тести перекривають пропагацію без hidden revoke.
- ✅ C.3 refresh exception classification — DONE через decoder (без домен-translation layer, як обговорено).
- ✅ C.4 `@Retryable` уніфіковано — DONE. `spring-retry` + `@EnableRetry` підключено; `markRevoked` і `persistRefreshed` мають `@Retryable(ObjectOptimisticLockingFailureException.class, maxAttempts=3, backoff=50ms/2x)`; з audit-методів `@Retryable` прибрано. Залишається — задокументувати правило в `token-management.md`.
- ✅ C.5 async fire-and-forget для audit-методів — DONE у частині коду: `recordSuccessfulUse` / `recordFailure` мають `@Async("iamAuditTaskExecutor")`; `TaskExecutorConfig` з обмеженою чергою (core=2, max=4, queue=500, `CallerRunsPolicy`); `markRevoked` лишився синхронним. Залишається — `try/catch` + Prometheus metric у самих async-методах і non-blocking assertion у тестах.
- ✅ C.6 marker `TransientException` — DONE (impl-и `OAuthTransientException`, `LockAcquiringException`). Caller-side retry — коли Ingestion.
- C.7 прибрати `provider` з `markRevoked` API.
- C.9 прибрати `SourceStatus.ERROR` з enum + Flyway міграція.
- 🆕 C.10 Resilience4j circuit-breaker на межі IAM для `OAuthClientCredentialsException` — TODO.
- ✅ C.11 Feign per-client child-context bug — DONE (`@Configuration` прибрано з config-класів).

**Policy docs (Phase D) — виносяться поза цей план у `docs/technical-notes/` в міру появи технічної потреби.**

---

## Dependency graph (короткий візуал)

```
A.* (reading)  ─────  DONE  (+ ADR 0010/0011 — до прочитання)
   ↓
B.1..B.5 core     B.6 Google login · B.13 identity+connect · B.7/B.8 JWT   B.9 (partial) ── B.10 problem details ── B.11 logout
   DONE                       TODO (ADR 0011)                                    TODO                    TODO
   ↓                                                                                            ↑
B.12 integration tests + Feign decoder unit tests (partial, expanding)                          │
                                                                                                │
   ↓                                                                                            │
C.2 ✅   C.3 ✅   C.4 ✅   C.5 ✅   C.6 ✅   C.11 ✅ ── other C.* still TODO                    │
```

## Progress tracking

Головна правда — чекбокси в цьому файлі. SQL todos у сесії — тимчасовий tracking
для assistant-side coordination; після завершення сесії залишається цей файл.

## Notes для asssistant-а

- **Це навчальний проєкт.** Не пиши код за user'а. Питай дозволу перед будь-яким `create`/`edit` файлу в `src/`. Технічні дрібниці (`.gitignore`, `pom.xml`, `compose.yaml`, `docs/`) — можна без питання.
- Working agreements: показувати диф, питати перед push / delete.
- `com.aperitivo` НЕ ВИКОРИСТОВУВАТИ — тільки `dev.amishko.aperitivo`.
