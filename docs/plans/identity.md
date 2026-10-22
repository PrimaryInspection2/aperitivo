
# Identity & Access — план реалізації

Робочий план BC **Identity & Access** під чинну модель:
- [ADR 0009](../adr/0009-identity-spring-security.md) — identity всередині застосунку, власний RS256 issuer;
- [ADR 0011](../adr/0011-identity-provider-signup.md) — логін лише через Google, Strava — data source;
- [ADR 0012](../adr/0012-spa-mobile-stateless-api.md) — SPA і мобілка, логін обміном Google ID token, одна stateless chain, Strava connect, керований SPA;
- [ADR 0013](../adr/0013-access-and-refresh-tokens.md) — access на 15 хв + ротований refresh.

Стан «зроблено» звірено з гілкою `main` (коміт `a77b09a`) 2026-10-22. Попередні версії плану (три редизайни) лежать в архіві: [identity-2026-10.md](../archived/plans/identity-2026-10.md), [identity-adr-0011.md](../archived/plans/identity-adr-0011.md). Нічого звідти не реалізовувати без звірки з цим файлом.

---

## 📖 Читати перед стартом

**Рішення:**
- [ ] [ADR 0004](../adr/0004-user-model-and-connected-sources.md), [ADR 0009](../adr/0009-identity-spring-security.md), [ADR 0011](../adr/0011-identity-provider-signup.md), [ADR 0012](../adr/0012-spa-mobile-stateless-api.md), [ADR 0013](../adr/0013-access-and-refresh-tokens.md) — у такому порядку.
- [ ] [ADR 0003](../adr/0003-token-storage-strategy.md) — Strava-токени в Postgres, AES-GCM.

**IAM BC:**
- [ ] [`api.md`](../contexts/identity-access/api.md) — endpoints, модель автентифікації, security chain, контракти `/api/auth/*` і `/api/connect/strava`.
- [ ] [`domain-model.md`](../contexts/identity-access/domain-model.md), [`database.md`](../contexts/identity-access/database.md) (`user_identities`, `refresh_tokens`), [`events.md`](../contexts/identity-access/events.md).
- [ ] Діаграми: [google-login](../contexts/identity-access/diagrams/sequence/google-login.md), [strava-connect](../contexts/identity-access/diagrams/sequence/strava-connect.md), [token-refresh](../contexts/identity-access/diagrams/sequence/token-refresh.md), [revocation-flow](../contexts/identity-access/diagrams/sequence/revocation-flow.md).

**Технічні нотатки:**
- [ ] [`jwt-and-keys.md`](../technical-notes/jwt-and-keys.md) — JwtEncoder, ключі, `kid`, refresh-токен, logout.
- [ ] [`token-management.md`](../technical-notes/token-management.md), [`encryption-at-rest.md`](../technical-notes/encryption-at-rest.md), [`spring-modulith-boundaries.md`](../technical-notes/spring-modulith-boundaries.md).
- [ ] [`integrations/strava/oauth-flow.md`](../integrations/strava/oauth-flow.md) — client-driven connect.

**Зовнішнє:**
- [ ] [Google — Verify the Google ID token on your server side](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)
- [ ] [Spring Security 7 — Resource Server JWT](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/jwt.html), [OAuth 2.0 Login — advanced (ID token decoder)](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/login/advanced.html)
- [ ] [RFC 9700 §4.14 — Refresh Token Protection](https://www.rfc-editor.org/rfc/rfc9700#section-4.14)

---

## ✅ Зроблено

- **Каркас модуля:** пакети, `package-info`, `ApplicationModules.verify()` в `AperitivoApplicationTests`.
- **Схема і дані:** Flyway `V1`–`V4` (schema, `users`, `connected_sources`, `user_identities` — поки без констрейнтів, див. крок 2); тестове дзеркало `event_publication` винесене у `V999`, колізії версій немає; `UserEntity`, `ConnectedSourceEntity`, `UserIdentityEntity` (plain data) і їхні репозиторії; між агрегатами лише `UUID`-посилання.
- **AES-GCM:** `AesGcmCipher` (96-бітний nonce на кожне шифрування), `EncryptedBytesConverter`, `EncryptionProperties`; тести `AesGcmCipherTest`, `EncryptedBytesConverterTest`, `EncryptedBytesConverterIT`.
- **TokenManager (Strava):** published-інтерфейс `TokenManager` (`markRevoked(userId, reason)` уже без `provider`); `StravaTokenManager` з lazy refresh, per-user Redis lock (`PerUserTokenRefreshLock`), `markRevoked` з guard-ом переходу і `IntegrationRevokedEvent`, аудит `recordSuccessfulUse`/`recordFailure` (`@Async("iamAuditTaskExecutor")`, обмежена черга, `try/catch` з логуванням усередині), `@Retryable` на `markRevoked`/`persistRefreshed` (`@EnableRetry`).
- **Помилки OAuth Strava:** Feign-клієнти в окремих child-контекстах, `StravaOAuthClientErrorDecoder` → `OAuthBadRequestException` / `OAuthClientCredentialsException` / `OAuthTransientException` / `OAuthUnexpectedStatusException` у published surface; базовий `TransientException`; прихованого revoke в `getValidAccessToken` немає.
- **Провіженінг логіну (частково):** `OAuth2GoogleLoginCallbackService.handleLogin` створює `User` + `UserIdentity` за `(provider, providerUserId)` і емітить `UserRegisteredEvent` для нового юзера; гілка returning user оновлює `lastLoginAt` (поки `Instant.now()`, не `Clock`).
- **Тести:** Testcontainers Postgres + Redis (`BaseIT`); IT — `StravaTokenManagerIT`, `StravaTokenManagerRetryIT`, `StravaTokenStateServiceIT`, `PerUserTokenRefreshLockIT` / `…ConcurrencyIT`, `EncryptedBytesConverterIT`, `TransactionalEventDeliveryIT`, `IamAuditTaskExecutorIT`, `IdentityPropertiesBindingIT`; unit — `AccessTokenIssuerTest`, `StravaOAuthClientErrorDecoderTest`, `AesGcmCipherTest`, `EncryptedBytesConverterTest`, `PerUserTokenRefreshLockUnitTest`, `TokenRefreshLockPropertiesTest`.

Чинна security: `oauth2Login`/`browserChain` уже прибрано; дві chain — `apiChain` (`/api/**`, resource server з нашим decoder-ом, `STATELESS`) + `fallbackChain` (без matcher-а: `/.well-known/**`, swagger, health/prometheus — `permitAll`, решта `denyAll`). Залишки логіну через redirect (`OAuth2GoogleLogin*Handler`, `LoginProperties`) прибирає крок 9.

---

## Порядок і залежності

```
2. user_identities: констрейнти + returning user
                         │
                         ├──> 3. Видача й валідація нашого JWT ──> 4. Логін: POST /api/auth/google ──> 5. Refresh + logout
                         │                                     │
                         │                                     └──> 6. Strava connect ──> 7. Події за контрактом
                         │
                         └──> 8. Problem Details (після 4)        9. Чистка + anti-drift (після 4–6)
10. Hardening TokenManager — незалежно
```

---

## Крок 1 — Розвести Flyway-версії ✅

- [x] Тестовий `V4__…_for_itest.sql` видалено, лишився `V999__create_event_publication_table_for_itest.sql` (коміт `328fd58`).
- [x] Правило «тестові міграції — від `V900`» зафіксовано в [`database.md` §Migrations](../contexts/identity-access/database.md).

## Крок 2 — `user_identities` до схеми з `database.md`

- [ ] Додати в `V4` `uq_user_identities_provider_identity (provider, provider_user_id)`, `uq_user_identities_user_provider (user_id, provider)`, `ix_user_identities_user`. Міграцію ще ніде не застосовано, тож правити на місці законно.
- [ ] Узгодити `last_login_at` vs `updated_at` у міграції, entity і `database.md`.
- [x] Гілка returning user у `handleLogin`: оновити `lastLoginAt`.
- [ ] Гонка першого логіну: unique constraint + `DataIntegrityViolationException` → повторний read (pattern 2 з [`idempotency-and-outbox.md`](../technical-notes/idempotency-and-outbox.md)).
- [ ] **DoD:** IT з двома паралельними першими логінами дає рівно один `User`.

## Крок 3 — Видача й валідація нашого JWT

- [x] Завантаження RSA-ключа (base64 PEM з конфігу або файлу), `kid`; бін `JwtEncoder` (Nimbus).
- [x] `AccessTokenIssuer`: claims `sub` = `user_id`, `iss`, `aud` = `aperitivo-api`, `iat`, `exp` = 15 хв, `jti`, і нічого більше.
- [x] `JwtDecoder` для **нашого** токена: підпис, `iss`, `aud`, `exp`.
- [ ] Конвертер JWT → `CurrentUser(UUID userId)` у published API IAM.
- [ ] Одна stateless `SecurityFilterChain`, deny by default ([api.md §Security filter chain](../contexts/identity-access/api.md)): без `securityMatcher`, `STATELESS`, CSRF off, CORS off, наш decoder передано явно, `AuthenticationEntryPoint` → `401` Problem Details.
- [x] `GET /.well-known/jwks.json`.
- [ ] `GET /api/me`.
- [ ] **Тести:** невідомий шлях → відмова; жодна відповідь не ставить `JSESSIONID`; протухлий / чужий токен → `401`.

## Крок 4 — Логін: `POST /api/auth/google`

- [ ] `GoogleIdTokenVerifier` (не бін типу `JwtDecoder`): `NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com")` + `JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(googleRegistration))` + `JwtClaimValidator` на `email_verified` + опційний `nonce`.
- [ ] Перевірити на реальному токені форму `iss` (`accounts.google.com` vs `https://…`).
- [ ] Контролер `POST /api/auth/google { idToken }` (JSON-only): верифікація → `handleLogin` → access у тілі (`Cache-Control: no-store`) + refresh-cookie (крок 5).
- [ ] **Тести:** валідний / невалідний ID token (без `email_verified`, чужий `aud`, прострочений); **валідний Google ID token як bearer на `/api/**` → `401`**.

## Крок 5 — Refresh-токени і logout ([ADR 0013](../adr/0013-access-and-refresh-tokens.md))

- [ ] Flyway-міграція `refresh_tokens` за [`database.md`](../contexts/identity-access/database.md).
- [ ] `RefreshTokenService`: 256 біт (base64url), у БД лише SHA-256; нова сім'я на логін; ротація на кожен refresh; повторне використання → відкликати сім'ю; grace-вікно 10–30 с; 14 днів idle / 30 днів абсолютний ліміт.
- [ ] Cookie `refresh_token`: `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`.
- [ ] `POST /api/auth/refresh` і `POST /api/auth/logout` (ідемпотентний, працює з протухлим access).
- [ ] «Log out everywhere»; job, що чистить прострочені рядки.
- [ ] **Тести:** ротація, reuse → сім'ю відкликано, grace-вікно, logout, `Cache-Control: no-store`, `permitAll` на `/api/auth/**` поводиться коректно.

## Крок 6 — Strava connect ([ADR 0012](../adr/0012-spa-mobile-stateless-api.md) §4)

- [ ] `ClientRegistration` «strava» лише як конфіг: `redirect-uri` = SPA-маршрут `https://aperitivo.app/connect/strava/callback` (per environment), scopes `read,activity:read_all,profile:read_all`.
- [ ] `POST /api/connect/strava { code, scope }` під bearer: обмін через `RestClientAuthorizationCodeTokenResponseClient`; без `activity:read_all` → `400`; `user_id` лише з `CurrentUser`.
- [ ] Після обміну явний `GET /api/v3/athlete` → athlete `id` як `providerUserId`.
- [ ] `ConnectedSourceService.connect`: create/reactivate, AES-GCM, `ACTIVE`; `IntegrationConnectedEvent` лише на переході в `ACTIVE`.
- [ ] Конфлікт (athlete прив'язаний до іншого `User`) → `409 …/errors/source-already-linked`.
- [ ] `GET /api/me/sources`, `DELETE /api/me/sources/{provider}` (`markRevoked`, `USER_REQUESTED`).
- [ ] **Тести:** Strava stub (WireMock / MockWebServer); неповні scopes; повторний connect; конфлікт.

## Крок 7 — Події за контрактом

- [ ] `UserRegisteredEvent(UUID id)` → `userId`, `occurredAt`; `IntegrationConnectedEvent(UUID id)` → `userId`, `connectedSourceId`, `provider`, `occurredAt` (див. [`events.md`](../contexts/identity-access/events.md)), `occurredAt` з `Clock`.
- [ ] Звірити `IntegrationRevokedEvent` і [`events/catalog.md`](../events/catalog.md).
- [ ] **DoD:** record-и збігаються з `events.md` поле в поле; тест публікації зелений.

## Крок 8 — RFC 7807 Problem Details

- [ ] `spring.mvc.problemdetails.enabled=true`.
- [ ] `@ExceptionHandler` для IAM-винятків (`IntegrationNotActiveException`, `ConnectedSourceNotFoundException`, OAuth-винятки, `LockAcquiringException`) і `type` URIs за [`api-errors.md`](../conventions/api-errors.md).
- [ ] Ніколи не віддавати в body токени, SQL чи stack trace.

## Крок 9 — Чистка і anti-drift

- [ ] Видалити `oauth2Login`, `oauth2Client`-фільтр, `browserChain`, `OAuth2GoogleLoginSuccessHandler`, `OAuth2GoogleLoginFailureHandler`, `LoginProperties` (якщо зайвий), `OAuth2ClientController`, будь-яке використання `HttpSession`.
- [ ] Узгодити назву сервісу логіну: у коді `OAuth2GoogleLoginCallbackService`, у [`domain-model.md`](../contexts/identity-access/domain-model.md) `LoginService`.
- [ ] `GET /api/me/identities`, `DELETE /api/me/identities/{provider}` + інваріант «не можна видалити останній sign-in метод» (тест).
- [ ] Anti-drift тест: жоден data-source провайдер не приймається для логіну.
- [ ] `ApplicationModules.verify()` зелений.

## Крок 10 — Hardening `TokenManager` (незалежно)

- [ ] **`UserContactReader`** (published port): інтерфейс у base package, реалізація поверх `UserRepository` (проєкція на одну колонку); адресу не класти в payload подій.
- [ ] **Прибрати `ConnectedSourceStatus.ERROR`** (немає методу, що його ставить): enum, міграція/constraint, згадки в `domain-model.md` і `events.md`.
- [ ] **Resilience4j circuit breaker** `strava-oauth` на межі IAM: `OAuthClientCredentialsException` відкриває breaker; `OAuthBadRequestException` — у `ignore-exceptions` (per-user dead token не має вимикати всіх).
- [ ] [`token-management.md`](../technical-notes/token-management.md): таблиця «HTTP-код → виняток → дія caller-а» під фактичні імена; правило `@Retryable` у §Concurrency policy.
- [ ] `@ApplicationModuleTest` для ізольованого запуску модуля.

---

## Progress tracking

| Крок | Статус |
|---|---|
| 1. Flyway-колізія | ✅ |
| 2. `user_identities` | 🟡 |
| 3. Наш JWT + chain | 🟡 видача/валідація готові, chain і `CurrentUser` — ні |
| 4. `POST /api/auth/google` | ⬜️ |
| 5. Refresh + logout | ⬜️ |
| 6. Strava connect | ⬜️ |
| 7. Події | ⬜️ |
| 8. Problem Details | ⬜️ |
| 9. Чистка + anti-drift | ⬜️ |
| 10. Hardening | ⬜️ |