# Technical Note: Encryption at Rest

How Aperitivo protects sensitive data stored in Postgres — the AES-GCM column-encryption
mechanism, key management (MVP env-key vs production envelope encryption), what is and isn't
encrypted, key rotation, and the threat model it defends against.

This note is the **cross-cutting encryption discipline**. The
[token-management note](token-management.md) covers the *token lifecycle* (refresh, revocation,
concurrency) and references the encryption details here; this note owns the encryption mechanism
itself. Decision context: [ADR 0003](../adr/0003-token-storage-strategy.md) (tokens in Postgres,
AES-GCM encrypted).

## What gets encrypted, and what does not

Encryption at rest is **targeted, not blanket**. Postgres-level full-disk/TDE encryption (if the
deployment provides it) protects against stolen disks; **application-level column encryption**
protects against a leaked *database* (a dump, a backup, an over-broad read) — a different and more
likely threat for a web app. We apply column encryption to the data whose leakage is genuinely
damaging:

| Data | Encrypted? | Why |
|---|---|---|
| Strava **access / refresh tokens** (`connected_sources`) | ✅ AES-GCM | a leak grants API access to users' Strava accounts — the crown jewels |
| JWT **signing key** (RS256 private key) | ✅ (key custody, not a column) | held as a secret, not in a table — see key management |
| User profile (name, athlete id) | ❌ | low sensitivity; not worth the operational cost / queryability loss |
| Workout / activity data, streams | ❌ | not secret in the security sense; encrypting would break analytics queries |
| Notification content, preferences | ❌ | derived, low-sensitivity |

The principle: encrypt **credentials and secrets**, not ordinary domain data. Encrypting everything
would cripple queryability (you cannot index or range-query ciphertext) for no real security gain on
non-secret data. The token columns are the one place where the data is both stored and
catastrophic-if-leaked, so they are the focus.

> Note this is why tokens live in Postgres at all and not Redis ([ADR 0003](../adr/0003-token-storage-strategy.md)):
> Redis has no built-in encryption at rest (its RDB/AOF dumps would be plaintext unless the app
> encrypts anyway), so we'd be doing app-level encryption regardless — and Postgres additionally
> gives transactional coupling.

## The mechanism — AES-GCM column encryption

**Algorithm: AES-GCM** (Galois/Counter Mode) — *authenticated* encryption providing both
confidentiality and integrity. The integrity part matters: GCM's authentication tag means tampered
ciphertext fails to decrypt rather than silently yielding garbage, so a modified token column is
detected, not used.

**Per-value nonce.** Every encryption uses a **fresh random 96-bit nonce** (IV), stored alongside
the ciphertext. This is non-negotiable for GCM: reusing a (key, nonce) pair catastrophically breaks
the mode. Fresh-random-per-encryption is the standard safe construction; the nonce is not secret and
is stored with the value.

**Stored format** per encrypted column: `nonce ‖ ciphertext ‖ auth-tag` (the GCM tag is appended by
the cipher), base64-encoded into a `TEXT` column. Decryption splits off the nonce, verifies the tag,
returns plaintext.

### Implementation — a JPA `AttributeConverter`

Encryption is transparent to application code via a converter:

```java
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        byte[] nonce = randomNonce(12);                 // 96-bit, fresh per call
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(ENCRYPT_MODE, currentKey(), new GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(plaintext.getBytes(UTF_8));
        return base64(concat(nonce, ct));               // nonce ‖ ciphertext+tag
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) return null;
        byte[] raw = unbase64(stored);
        byte[] nonce = first(raw, 12);
        byte[] ct    = rest(raw, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(DECRYPT_MODE, keyFor(stored), new GCMParameterSpec(128, nonce));
        return new String(c.doFinal(ct), UTF_8);        // tag verified here; throws on tamper
    }
}
```

```java
@Entity
class ConnectedSourceEntity {
    @Convert(converter = EncryptedStringConverter.class)
    private String accessToken;     // stored encrypted, used as plaintext in code

    @Convert(converter = EncryptedStringConverter.class)
    private String refreshToken;
}
```

The converter makes encryption invisible to the rest of the code: the `TokenManager` works with
plaintext `String`s, the database holds ciphertext. (An explicit encrypt/decrypt in the repository
is the alternative if we prefer the operation to be visible rather than magic — a deliberate
readability-vs-transparency choice, noted in [token-management.md](token-management.md).)

**Never logged.** Decrypted tokens never appear in logs, OTel spans, or error messages. Token access
is auditable via structured logs ("token used for user X, operation Y") — the *fact* of access, not
the value.

## Key management

This is where MVP and production deliberately differ.

### MVP — single env-sourced key

A single AES-256 key, sourced from an **environment variable or mounted secret**, loaded at startup.
Acceptable as a starting point for a single-VPS, invite-only deployment. The key is **not** in the
database, **not** in the repo, **not** in config committed to git — it is injected at deploy time.

Limitations, stated honestly:
- Rotating the key means **re-encrypting every token column** (decrypt-with-old, encrypt-with-new) —
  a migration, not a config flip.
- The running process holds the key in memory; a memory dump of a compromised host exposes it.
- One key protects everything; no per-tenant or per-data-class isolation.

These are acceptable for MVP and explicitly **not** acceptable as the production end state — hence
envelope encryption.

### Production hardening — envelope encryption (deferred)

The production target ([ADR 0003](../adr/0003-token-storage-strategy.md) follow-up) is **envelope
encryption** with a KMS/Vault-managed key:

```
KEK (key-encryption key)  ── held in Vault Transit / cloud KMS, never leaves it
   │  wraps/unwraps
   ▼
DEK (data-encryption key) ── encrypts the token columns; stored wrapped (encrypted by KEK)
   │  encrypts
   ▼
token ciphertext in Postgres
```

How it works:
- The app holds only the **wrapped** DEK. To use it, it asks Vault/KMS to **unwrap** (decrypt) the
  DEK — the app authenticates via AppRole / workload identity and **never holds the KEK**.
- The unwrapped DEK lives briefly in memory to encrypt/decrypt columns; the KEK never enters the app.

Why this is the target:
- **Key rotation without re-encrypting all rows.** Rotate the KEK and re-wrap the DEK — the token
  ciphertext (encrypted under the DEK) is untouched. This is the decisive operational win over the
  MVP single-key approach.
- **Audit trail of key usage** — Vault/KMS logs every unwrap.
- **Blast-radius reduction** — a DB leak yields ciphertext + a *wrapped* DEK, useless without the
  KEK that never left the KMS.

This is a strong, broadly-applicable production pattern and a deliberate **learning target** — but
it is **not** in the MVP. The MVP env-key is the honest starting point; envelope encryption is the
documented next step, the same way the JWT signing key's custody hardens from env-sourced to
KMS-backed ([jwt-and-keys.md](jwt-and-keys.md)).

> The JWT signing key (RS256 private key) follows the **same** custody trajectory: env-sourced in
> MVP, KMS/Vault-backed in production. Encryption-at-rest (token columns) and signing-key custody are
> two faces of the same "secrets management" maturation; both are env-key now, both target KMS later.

## Key rotation

Two rotation stories, depending on the era:

- **MVP (single key):** rotation is a migration — stand up the new key alongside the old, re-encrypt
  every token column (decrypt-old → encrypt-new) in a batch, then retire the old key. The
  `keyFor(stored)` hook in the converter (a key-id prefix on the stored value) lets old and new
  ciphertext coexist during the migration window.
- **Envelope (production):** rotate the **KEK** in Vault/KMS and **re-wrap the DEK**; token
  ciphertext is never touched. Optionally rotate the DEK too (which *does* require re-encryption) on
  a slower cadence. This is the payoff — routine key rotation stops being a data migration.

The stored-value key-id prefix (`v1:`, `v2:`) is the small piece of forethought that makes either
rotation non-disruptive: the converter reads the prefix to pick the right key, so both eras' values
decrypt during overlap.

## Threat model — what this defends against

| Threat | Defended? | By what |
|---|---|---|
| **Database dump / backup leak** | ✅ | token columns are ciphertext; useless without the key (MVP) or the KEK (envelope) |
| **Over-broad DB read** (SQL injection, a too-powerful read role) | ✅ | same — the reader gets ciphertext |
| **Tampered token column** | ✅ | GCM auth tag fails decryption → detected, not used |
| **Stolen disk / cold backup media** | ✅ (+ Postgres TDE if present) | column encryption already covers the sensitive columns |
| **Compromised running host** (memory access) | ⚠️ partial | MVP: key is in memory. Envelope: only the short-lived unwrapped DEK is in memory; KEK never is |
| **Compromised KMS/Vault** | ⚠️ | out of scope — Vault/KMS is the trust root; its own hardening is a separate operational concern |
| **Logged plaintext token** | ✅ | policy: tokens never logged; access is audited by fact, not value |

The honest summary: column encryption defeats **data-at-rest leaks** (the common, high-likelihood
threat for a web app), and envelope encryption additionally shrinks the **running-host** blast
radius. Neither defends against a fully-compromised live host with the key in memory — that is what
KMS-held KEKs and short-lived unwrapped DEKs mitigate, not eliminate.

## Operational notes

- **Migrations never touch the key.** Flyway creates the `TEXT` columns; encryption is purely
  application-side, so the migration tool never sees plaintext or the key. (Stated in the IAM/
  Ingestion database docs as "application-side AES-GCM; the migration tool never touches the key.")
- **Backups are safe by construction.** Because encryption is at the column level in the app, every
  Postgres backup naturally contains ciphertext — no separate "encrypt the backup" step, no second
  store to keep in sync.
- **Observability without exposure.** Metrics on encrypt/decrypt counts and failures (a decrypt
  failure spike = possible key mismatch or tampering); never the values.

## What is deliberately NOT done (MVP)

- **No envelope encryption yet** — single env-key in MVP; KMS/Vault-backed envelope is the documented
  production step.
- **No blanket/whole-database encryption** — only credentials/secrets are column-encrypted; domain
  data stays queryable plaintext.
- **No per-user / per-tenant keys** — one key (MVP) or one DEK (envelope) for all token columns;
  per-tenant key isolation is a much later, multi-tenant concern.
- **No client-side / end-to-end encryption** — the server necessarily uses the tokens to call Strava,
  so it must be able to decrypt; E2E is not applicable to this data.

## Related

- [ADR 0003: Token storage strategy](../adr/0003-token-storage-strategy.md) — the decision, with envelope encryption as a follow-up
- [Token management](token-management.md) — the token lifecycle that consumes this encryption
- [JWT issuance and validation](jwt-and-keys.md) — the signing key's parallel env→KMS custody path
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
