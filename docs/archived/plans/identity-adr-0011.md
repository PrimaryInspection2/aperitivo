# Work Plan: завершення міграції на ADR 0011 (Google login + connect on demand)

> **⚠️ Оновлено під [ADR 0012](../adr/0012-spa-mobile-stateless-api.md) і
> [ADR 0013](../adr/0013-access-and-refresh-tokens.md).** Після написання плану змінились три речі:
> - **Логін** — тепер обмін Google ID token: клієнт (React SPA, пізніше мобільний застосунок) сам
>   отримує ID token і шле `POST /api/auth/google { idToken }`; бекенд верифікує його Spring-компонентами
>   в `GoogleIdTokenVerifier`, провіженить `User` за `(issuer, sub)` (ADR 0011 без змін) і видає наші
>   токени. **Немає** `oauth2Login`, `HttpSession`, success/failure handler-ів і `browserChain`;
>   Thymeleaf/HTMX-демо прибрано.
> - **Життєвий цикл токенів** — access JWT на **15 хв** (JSON-тіло, `Authorization: Bearer`, у SPA —
>   лише в пам'яті) + опаковий **refresh-токен з ротацією** і reuse detection (hash у Postgres
>   `refresh_tokens`, cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`). Logout відкликає
>   family; `jti` denylist у MVP **немає**.
> - **Strava connect** — client-driven: SPA веде браузерну частину і `state`
>   (`redirect_uri` = SPA-маршрут `/connect/strava/callback`), бекенд лише обмінює code на
>   `POST /api/connect/strava` (`RestClientAuthorizationCodeTokenResponseClient`). Ніякого
>   `oauth2Client` redirect-фільтра і `/connect/**` на бекенді.
>
> Скасовані пункти лишені **закресленими** як запис спланованого; нові — поруч, без галочки.
> Механіка `TokenManager` / AES-GCM / refresh / revocation Strava-токенів — **без змін**.

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
B.8, B.11 (крок 3b), B.13 і додають те, чого в них не було.

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
~~`loginAuthorizationRequestResolver`, що звужує `oauth2Login` до однієї реєстрації;~~
~~`redirect-uri: {baseUrl}/connect/oauth2/code/{registrationId}` для Strava;~~
`user_identities` без токенів і без status-lifecycle.

> **ADR 0012:** `loginAuthorizationRequestResolver` іде разом з `oauth2Login`, а `redirect-uri`
> Strava тепер SPA-маршрут `https://aperitivo.app/connect/strava/callback`
> ([oauth-flow.md](../integrations/strava/oauth-flow.md)).

---

## Порядок і залежності

```
1. Flyway V4 collision ─┬─> 2. Констрейнти + lastLoginAt ──> 4. isNewUser routing
                        │
                        └─> 3. ID-token login + JWT + resource server ──> 5. Connect-персистенс ──> 6. Події
                                 │                                                                    │
                                 └─> 3b. Refresh-токени + logout (ADR 0013)     7. Чистка + anti-drift <──┘
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

Міграція створює таблицю **без жодного констрейнта**, хоча `database.md` описує три. Це не
косметика: `handleLogin` робить read-then-insert без захисту, тож два паралельні перші
логіни того самого Google-акаунта створять **два `UserEntity`** — рівно той дубль, проти
якого написана ADR 0011.

| Документовано в `database.md` | Є в `V4` |
|---|---|
| `uq_user_identities_provider_identity (provider, provider_user_id)` | ❌ |
| `uq_user_identities_user_provider (user_id, provider)` | ❌ |
| `ix_user_identities_user (user_id)` | ❌ |
| `last_login_at TIMESTAMPTZ NULL` | ❌ |
| `updated_at` — **відсутній** у доці | ⚠️ є в міграції та в entity |

### 📖 Читати перед кроком

- [`database.md` §user_identities + §Constraints and indexes](../contexts/identity-access/database.md) — цільова DDL і рационале кожного індексу.
- [`domain-model.md` §UserIdentityEntity](../contexts/identity-access/domain-model.md) (рядок ~63) та §Invariants — зокрема `lastLoginAt` і правило «одна provider-identity → рівно один User».
- [`idempotency-and-outbox.md` §Consume side — three dedup patterns](../technical-notes/idempotency-and-outbox.md) — **pattern 2 (unique-constraint dedup)** — це саме те, як ловити гонку: констрейнт + `DataIntegrityViolationException` + повторний read.
- [ADR 0011 §2 і §4](../adr/0011-identity-provider-signup.md) — чому ключ завжди `(issuer, sub)` і чому конфлікт — це `409`, а не re-assign.
- [`api-errors.md`](../conventions/api-errors.md) — форма помилки, якщо конфлікт вилазить у HTTP.

### Кроки

- [ ] Додати обидва unique-констрейнти та індекс у `V4` (міграція ще не застосована ніде — правити її на місці законно, окремої `V5` не треба).
- [ ] Вирішити розбіжність `last_login_at` vs `updated_at`: або міграція+entity доганяють доку, або доку виправити. Обрати **одне** і зробити консистентним у трьох місцях, де `lastLoginAt` уже описаний ([`domain-model.md`](../contexts/identity-access/domain-model.md), [`api.md`](../contexts/identity-access/api.md), [`google-login.md`](../contexts/identity-access/diagrams/sequence/google-login.md)).
- [ ] Реалізувати гілку returning-user у сервісі логіну — зараз вона не пише нічого.
- [ ] Обробити гонку first-login по pattern 2 (не покладатись на те, що «навряд чи станеться»).
- [ ] IT на конкурентний перший логін: два потоки, один `User` на виході.

### ✅ Definition of done

Констрейнти в БД; конкурентний IT зелений; `lastLoginAt` оновлюється; жодної розбіжності
між `V4`, entity і `database.md`.

---

## Крок 3 — ID-token login + JWT issuance + resource server

Зараз у `OAuth2GoogleLoginSuccessHandler` стоїть `//TODO issue JWT HERE`, тож логін дає
лише сесію. Наслідки: `apiChain` має `anyRequest().authenticated()` + `STATELESS` +
закоментований `oauth2ResourceServer` → `/api/**` недосяжний за жодних умов;
`/.well-known/jwks.json` у `permitAll`, але контролера немає.

~~**Питання, яке треба закрити саме тут:** браузерна сесія існує лише на час OAuth-редіректів,
чи вона і є автентифікація для браузера? Від відповіді залежить, чим захищений `/connect/**`
(зараз — сесією, а `api.md` описує cookie-JWT + resource server).~~

> **✅ Закрито [ADR 0012](../adr/0012-spa-mobile-stateless-api.md):** браузерної сесії немає
> взагалі. Логін — обмін Google ID token на `POST /api/auth/google`; один stateless чейн,
> deny by default; уся автентифікація API — наш bearer-токен. `/connect/**` на бекенді зникає:
> connect — це `POST /api/connect/strava` під bearer-ом (крок 5).

### 📖 Читати перед кроком

- [ADR 0012](../adr/0012-spa-mobile-stateless-api.md) — **керівна ADR кроку**: §2 ID-token exchange і верифікація Spring-компонентами, §3 один stateless чейн, deny by default, CSRF off.
- [ADR 0013](../adr/0013-access-and-refresh-tokens.md) §1 — claims і TTL access-токена, форма JSON-відповіді.
- [`jwt-and-keys.md`](../technical-notes/jwt-and-keys.md) — **головний технічний документ кроку**: §What the token is, §Issuing — `JwtEncoder`, §Validating — resource server, §Key handling.
- [ADR 0009](../adr/0009-identity-spring-security.md) — чому self-issued RS256 і чому без зовнішнього identity server.
- ~~[`api.md` §Authentication model](../contexts/identity-access/api.md) — «one token, two transports», атрибути cookie, `CookieOrHeaderBearerTokenResolver`, CSRF-міркування.~~ → [`api.md`](../contexts/identity-access/api.md) — модель автентифікації і контракти `/api/auth/*` після ADR 0012/0013.
- [`google-login.md`](../contexts/identity-access/diagrams/sequence/google-login.md) — ~~phase 5: що саме робить success handler~~ послідовність ID-token логіну і чому мінт **поза** транзакцією провіженінгу.
- [Google — Verify the Google ID token on your server side](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token) — що саме перевіряти в ID token.
- [`security.md`](../operations/security.md) — зберігання ключів, секрети.
- [Spring Security — JWT / JOSE](https://docs.spring.io/spring-security/reference/servlet/oauth2/jwt.html) — `JwtEncoder`, `NimbusJwtEncoder`, `JWKSource`.
- [Spring Security — OAuth 2.0 Resource Server / JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) — `JwtDecoder`, `BearerTokenResolver`, `JwtAuthenticationConverter`.

### Кроки

- [ ] RSA keypair з конфігу (env-driven, як `AES_GCM_SECRET`), `JwtEncoder` bean.
- [ ] ~~Claims рівно ті, що в ADR 0009: `sub` = internal `user_id`, `iss`, `iat`, `exp`, `jti`. **Ніяких** Strava-токенів і **не** Google `sub`.~~ → нижче, за ADR 0013.
- [ ] Claims за [ADR 0013](../adr/0013-access-and-refresh-tokens.md) §1: `sub` = internal `user_id`, `iss`, `aud` = `aperitivo-api`, `iat`, `exp` = `iat` + **15 хв**, `jti`. **Ніяких** Strava-токенів, email/імені і **не** Google `sub`.
- [ ] ~~Мінт у success handler після `handleLogin`, cookie `HttpOnly; Secure; SameSite=Lax`.~~ → скасовано ADR 0012: немає success handler-а і JWT-cookie.
- [ ] `GoogleIdTokenVerifier` (IAM): `NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com")` + `OidcIdTokenValidator(googleRegistration)` + `JwtClaimValidator` для `email_verified` + порівняння `nonce`, якщо клієнт його передав. **Не** `JwtDecoder`-bean.
- [ ] `POST /api/auth/google { idToken }` — верифікація → `handleLogin` (провіженінг без змін) → мінт access-токена **поза** транзакцією → `{ accessToken, tokenType, expiresIn }` у тілі + refresh-cookie (крок 3b).
- [ ] `@RestController` для `/.well-known/jwks.json` (зараз шлях у `permitAll`, але 404).
- [ ] ~~Розкоментувати й дописати `oauth2ResourceServer` в `apiChain` + `BearerTokenResolver`, що читає header, потім cookie.~~ → скасовано: cookie-транспорту для access-токена немає.
- [ ] Один stateless `SecurityFilterChain` замість `apiChain` + `browserChain`: **без** `securityMatcher`, `STATELESS`, CSRF off, правила з ADR 0012 §3, `denyAll` для решти.
- [ ] `oauth2ResourceServer` з **нашим** декодером явно (`.jwt(j -> j.decoder(ourJwtDecoder))`): підпис, `iss`, `aud`, `exp`.
- [ ] Конвертер JWT → `CurrentUser(UUID userId)` у published API IAM.
- [ ] Кастомний `AuthenticationEntryPoint` → `401` з Problem Details.
- [ ] ~~Визначитись із `/connect/**`: який чейн його матчить і чим він автентифікований.~~ → закрито ADR 0012 (див. вище).
- [ ] Перший захищений endpoint `GET /api/me` як smoke-перевірка.
- [ ] Тести: валідний Google ID token як bearer на `/api/**` → `401`; жодна відповідь не ставить `JSESSIONID`; невідомий шлях → відмова (`denyAll`); `permitAll`-endpoint зі простроченим bearer-ом → `401` (клієнти не шлють `Authorization` на `/api/auth/**`); `Cache-Control: no-store` на відповідях з токенами.

### ✅ Definition of done

~~Логін кладе JWT у cookie; `GET /api/me` віддає дані під тим самим токеном і в header-, і в
cookie-формі; JWKS віддає публічний ключ.~~

`POST /api/auth/google` з валідним Google ID token повертає access-токен у тілі;
`GET /api/me` віддає дані з `Authorization: Bearer`; JWKS віддає публічний ключ; тести з
останнього пункту зелені.

---

## Крок 3b — Refresh-токени і logout (ADR 0013)

Нове з [ADR 0013](../adr/0013-access-and-refresh-tokens.md): 15-хвилинний access-токен без
refresh-у означав би повторний Google-логін на кожне перезавантаження SPA. Замінює колишній
B.11 «Logout + `jti` denylist» з [identity.md](identity.md).

### 📖 Читати перед кроком

- [ADR 0013](../adr/0013-access-and-refresh-tokens.md) — §2 refresh-токен, §3 ротація і reuse detection, §4 logout, §5 чому без denylist, §6 чому Postgres.
- [`jwt-and-keys.md` §The refresh token, §Logout / revocation](../technical-notes/jwt-and-keys.md).
- [`database.md`](../contexts/identity-access/database.md) — таблиця `refresh_tokens`.
- [RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700#section-4.14) — refresh-token rotation для public clients.

### Кроки

- [ ] Flyway-міграція `refresh_tokens(id, token_hash UNIQUE, user_id, family_id, expires_at, used_at, revoked_at, created_at)`.
- [ ] `RefreshTokenService`: 256 випадкових біт, base64url, зберігається лише SHA-256 hash; нова family на кожен логін; ротація (`used_at = now` + новий токен у тій самій family); reuse уже використаного → revoke всієї family; grace window 10–30 с; 14 днів idle / 30 днів absolute.
- [ ] Refresh-cookie `refresh_token`: `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`.
- [ ] `POST /api/auth/refresh` — за cookie, JSON-only; новий access-токен у тілі + ротований cookie.
- [ ] `POST /api/auth/logout` — revoke family поточного refresh-токена, очистити cookie (`Max-Age=0`, той самий `Path`), ідемпотентно; працює з простроченим access-токеном.
- [ ] Періодичне видалення рядків після `expires_at`.
- [ ] Тести: ротація; reuse → revoke family; повтор у grace window не вважається reuse; прострочений/відкликаний токен → `401`; logout ідемпотентний; `Cache-Control: no-store` на відповідях `/api/auth/*`.

### ✅ Definition of done

Refresh ротує токен і видає новий access-токен; повторне використання старого токена
відкликає family; logout очищає cookie і family; тести зелені.

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

- [ ] ~~Використати `LoginResult.isNewUser` у success handler для вибору target URL.~~ → скасовано ADR 0012: success handler-а немає, landing page обирає SPA.
- [ ] Віддати SPA ознаку «новий юзер / немає джерел» — як саме (поле у відповіді `POST /api/auth/google` чи `GET /api/me/sources`) вирішується під час реалізації разом з [`api.md`](../contexts/identity-access/api.md).
- [ ] ~~Onboarding-guard: поки в юзера нема жодного `ConnectedSource` — роутити на connect (не лише для щойно створеного; юзер міг відключити джерело).~~ → тепер route guard у SPA (нижче).
- [ ] Onboarding-guard у SPA: поки в юзера нема жодного `ConnectedSource` — роутити на connect (не лише для щойно створеного; юзер міг відключити джерело).
- [ ] ~~Врахувати, що `SavedRequestAwareAuthenticationSuccessHandler` має власну логіку saved-request — вирішити, що переважає.~~ → скасовано: немає success handler-а і saved request.

### ✅ Definition of done

Новий юзер після логіну опиняється на connect-сторінці; повторний логін з активним
джерелом — на home.

---

## Крок 5 — Connect-флоу починає щось зберігати

`oauth2Client` сконфігурований і зробить code-exchange, але результат осяде в дефолтному
in-memory сторі — у `connected_sources` не потрапить нічого. Симптом, який це доводить:
`ConnectedSourceEntityMapper` і `OAuth2TokensMapper` зараз мають **нуль викликів**.

> **⚠️ ADR 0012:** підпитання 1–2 нижче знято — connect тепер `POST /api/connect/strava` під
> bearer-ом: code-exchange робить `RestClientAuthorizationCodeTokenResponseClient` з реєстрацією
> `strava`, а `userId` — з `CurrentUser`. Підпитання 3 і перевірка scope лишаються.

Три підпитання, які тут доведеться розв'язати:

1. **Де seam.** В `oauth2Client` немає `AuthenticationSuccessHandler` — шукай компонент, який фреймворк смикає одразу після обміну і який має метод `save…`.
2. **Чий principal.** `authentication.getName()` після Google-логіну = Google `sub`, а не наш `user_id`. Ключ має бути стабільним і не залежати від провайдера.
3. **Звідки athlete id.** `StravaUserMapper` видалено разом із диспетчером. Strava кладе об'єкт `athlete` прямо у token response — дивись `OAuth2AccessTokenResponse#getAdditionalParameters`.

Плюс: `GrantedScopeValidator` зник, а перевірка «юзер не зняв галочку `activity:read_all`»
нікуди не поділась.

### 📖 Читати перед кроком

- [ADR 0012 §4](../adr/0012-spa-mobile-stateless-api.md) — **керівне рішення кроку**: client-driven connect; SPA володіє `state`, бекенд — code-exchange.
- [`strava-connect.md`](../contexts/identity-access/diagrams/sequence/strava-connect.md) — **головна діаграма кроку**~~; зокрема нота про те, чому `OAuth2AuthorizationCodeGrantFilter` матчить колбек за redirect-URI реєстрації~~.
- [`api.md`](../contexts/identity-access/api.md) — ~~таблиця відмінностей login vs connect~~ контракт `POST /api/connect/strava`, і звідки береться `user_id` (з нашого JWT, ніколи зі Strava).
- ~~[ADR 0011 §3](../adr/0011-identity-provider-signup.md) — чому саме `oauth2Client` і чому він не чіпає `SecurityContext`.~~ → механізм замінено ADR 0012 §4; інваріант «`user_id` лише з нашого токена» — без змін.
- [`domain-model.md` §ConnectedSourceService](../contexts/identity-access/domain-model.md) — контракт `linkSource`, реактивація vs новий рядок.
- [`database.md` §What reconnect actually does](../contexts/identity-access/database.md) — таблиця трьох сценаріїв реконекту; пояснює, чому «просто вставити новий рядок» неправильно в половині випадків.
- [ADR 0003](../adr/0003-token-storage-strategy.md) + [`encryption-at-rest.md`](../technical-notes/encryption-at-rest.md) — токени в `connected_sources` шифруються, шлях уже існує.
- [`token-management.md`](../technical-notes/token-management.md) — щоб не продублювати те, що вже вміє `TokenManager`.
- [`oauth-flow.md`](../integrations/strava/oauth-flow.md) — наш опис Strava-флоу (переписаний як **connect**, не login).
- [`api-errors.md`](../conventions/api-errors.md) — форма `409 …/errors/source-already-linked`.
- [Spring Security — Authorization Grant Support](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/authorization-grants.html) — ~~`OAuth2AuthorizationCodeGrantFilter`, поведінка на колбеку~~ `RestClientAuthorizationCodeTokenResponseClient` (обмін code на токени).
- ~~[Spring Security — OAuth2 Client Core Interfaces](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/core.html) — `OAuth2AuthorizedClientRepository` / `…Service`, їхні `save…` методи та ключування по principal.~~ → не потрібно: результат обміну персистимо самі в `connected_sources`.
- [Strava — Authentication](https://developers.strava.com/docs/authentication/) — що саме приходить у token response (включно з `athlete`) і як виглядає `scope` у колбеку.

### Кроки

- [ ] ~~Вибрати і реалізувати seam, який персистить результат code-exchange у `connected_sources`.~~ → скасовано ADR 0012: seam — наш контролер (нижче).
- [ ] `POST /api/connect/strava { code, scope }` під bearer-ом: обмін code через `RestClientAuthorizationCodeTokenResponseClient` + `strava` `ClientRegistration` (`client_secret` лише на сервері), `redirect-uri` = SPA-маршрут.
- [ ] Прибрати `oauth2Client` redirect-фільтр і його `/connect` authorization base URI.
- [ ] Взяти `userId` з нашої автентифікації, не з відповіді Strava.
- [ ] Дістати athlete id → `providerUserId`.
- [ ] Повернути перевірку granted scopes (юзер може зняти `activity:read_all`) → без нього `400`, нічого не лінкується.
- [ ] Реалізувати реактивацію-на-місці згідно з таблицею реконекту, а не сліпий insert.
- [ ] Конфлікт «джерело вже прив'язане до іншого User» → `409` з Problem Details.
- [ ] `GET /api/me/sources`, `DELETE /api/me/sources/{provider}` (→ `markRevoked`, `USER_REQUESTED`).
- [ ] IT: connect happy path, реконект після revoke, конфлікт, відмова в scope.

### ✅ Definition of done

Автентифікований юзер проходить ~~`/connect/strava`~~ connect зі SPA (`POST /api/connect/strava`)
і отримує `ACTIVE` рядок із зашифрованими токенами; `TokenManager.getValidAccessToken(userId)`
після цього працює.

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

- ~~[ADR 0011 §Risks](../adr/0011-identity-provider-signup.md) — прямо приписує **інтеграційний тест**: login-chain віддає рівно identity-реєстрації, жоден data-source registration не досяжний з login base-URI.~~ → після ADR 0012 login-chain і login base-URI немає.
- [ADR 0012 §Risks](../adr/0012-spa-mobile-stateless-api.md) — нові anti-drift тести: Google ID token не приймається як bearer; невідомий шлях → `denyAll`; без `JSESSIONID`.
- [`domain-model.md` §Services](../contexts/identity-access/domain-model.md) — канонічні імена сервісів і межі їхньої відповідальності.
- [`naming.md`](../conventions/naming.md) — суфікси, і окремо правило про два різні `sub`.
- [`spring-modulith-boundaries.md`](../technical-notes/spring-modulith-boundaries.md) — що має бути в base package (API), а що в приватному; `ApplicationModules.verify()` у CI.
- [`api.md` §`GET /api/me/identities`](../contexts/identity-access/api.md) — інваріант «не можна видалити останній sign-in метод».
- [Google — OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect) — які клейми гарантовані, що робити з `email_verified`.

### Кроки

- [ ] Викинути або переписати `OAuth2ClientController` (це залишок debug-коду часів Strava-логіну).
- [ ] ~~Помилку в success handler віддавати так, щоб юзер бачив `/login?error=`, а не 500.~~ → скасовано ADR 0012: success handler видаляється.
- [ ] Видалити `oauth2Login`, `browserChain`, `OAuth2GoogleLoginSuccessHandler`, `OAuth2GoogleLoginFailureHandler`, будь-яке використання `HttpSession`, placeholder-контролер `/` і Thymeleaf/HTMX-демо.
- [ ] Узгодити імена сервісів із `domain-model.md` (або оновити доку, якщо код правіший).
- [ ] ~~Anti-drift IT з ADR §Risks.~~ → замінено anti-drift тестами ADR 0012 (крок 3).
- [ ] `GET /api/me/identities`, `DELETE /api/me/identities/{provider}` + інваріант останнього методу входу, покритий тестом.
- [ ] Переконатися, що `ApplicationModules.verify()` лишився зеленим після всіх переміщень.

### ✅ Definition of done

`ApplicationModules.verify()` зелений; ~~anti-drift тест падає, якщо хтось додасть Strava в
`oauth2Login`;~~ anti-drift тести ADR 0012 зелені; жодного мертвого коду з попередніх топологій
(ні Strava-логіну, ні `oauth2Login`/сесії).

---

## Наскрізний чек-лист

- [ ] Крок 1 — Flyway V4 розведено, IT стартують
- [ ] Крок 2 — констрейнти + `lastLoginAt`, гонка first-login закрита
- [ ] Крок 3 — ID-token логін, JWT мінтиться, resource server увімкнено
- [ ] Крок 3b — refresh-токени з ротацією, `/api/auth/refresh`, `/api/auth/logout`
- [ ] Крок 4 — `isNewUser` веде на connect
- [ ] Крок 5 — connect пише `connected_sources`
- [ ] Крок 6 — події відповідають `events.md`
- [ ] Крок 7 — чистка + anti-drift тест

Після закриття всіх кроків: оновити чекбокси B.6 / B.7 / B.8 / B.11 / B.13 у [`identity.md`](identity.md)
і статус [Phase 1](../business/roadmap.md#phase-1--identity--access) у roadmap.

---

## Related

- [ADR 0011](../adr/0011-identity-provider-signup.md) — керівне рішення цього плану
- [ADR 0012](../adr/0012-spa-mobile-stateless-api.md) — ID-token логін, stateless API, client-driven connect
- [ADR 0013](../adr/0013-access-and-refresh-tokens.md) — 15-хвилинний access-токен + ротований refresh-токен
- [ADR 0010 (superseded)](../adr/0010-single-canonical-login-provider-superseded.md) — що саме змінилось і що вціліло
- [identity.md](identity.md) — повний план IAM (Phase A reading, B.1–B.13, Phase C hardening)
- [`docs/contexts/identity-access/`](../contexts/identity-access/README.md) — BC deep dive
