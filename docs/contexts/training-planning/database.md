# Training Planning — Database Schema

Physical schema for the Training Planning BC: the `Plan` aggregate (plans → scheduled sessions →
planned targets), the match/compliance storage, constraints and indexes. Conventions: snake_case,
UUID PKs, `timestamptz`/`date` UTC-or-local as noted, Flyway per-BC. Aggregate-design rationale:
[domain-model.md](domain-model.md).

## Schema ownership

Planning owns its `planning` schema. It reads no other BC's tables. Cross-BC references are **plain
columns without FKs**:
- `user_id` → IAM `users.id`
- `matched_workout_id` → Catalog `workouts.id`

The FKs **within** this schema (`scheduled_sessions.plan_id`, `planned_targets.session_id`) are
intra-aggregate and therefore real — the same distinction drawn project-wide: FKs inside an
aggregate/BC, id-refs across boundaries.

## Tables

### plans (aggregate root)

```sql
CREATE TABLE plans (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,                -- id-ref to IAM, no FK
    name        TEXT NOT NULL,
    start_date  DATE NOT NULL,
    end_date    DATE NOT NULL,
    status      TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT|ACTIVE|COMPLETED|ARCHIVED
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A user's plans, most-recent first.
CREATE INDEX ix_plans_user_status ON plans (user_id, status, start_date DESC);
```

### scheduled_sessions

```sql
CREATE TABLE scheduled_sessions (
    id                 UUID PRIMARY KEY,
    plan_id            UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,  -- intra-aggregate FK
    session_date       DATE NOT NULL,            -- planned calendar date (athlete-local)
    local_time         TEXT NULL,                -- optional "HH:mm" athlete-local
    sport_type         TEXT NOT NULL,            -- SportType (shared with Catalog)
    status             TEXT NOT NULL DEFAULT 'PLANNED',  -- PLANNED|COMPLETED|MISSED|SKIPPED
    matched_workout_id UUID NULL,                -- id-ref to Catalog workout, no FK
    match_is_manual    BOOLEAN NOT NULL DEFAULT false,   -- manual override is sticky
    compliance_score   DOUBLE PRECISION NULL,    -- overall similarity, set on match
    version            BIGINT NOT NULL DEFAULT 0
);

-- Candidate selection for matching: a user's PLANNED sessions near a date, by sport.
CREATE INDEX ix_sessions_match_candidates
    ON scheduled_sessions (sport_type, session_date)
    WHERE status = 'PLANNED';

-- Calendar view + the scheduler's due/missed sweeps.
CREATE INDEX ix_sessions_plan_date ON scheduled_sessions (plan_id, session_date);

-- Reverse lookup: which session does this workout match? (re-eval on WorkoutUpdated/Deleted)
CREATE INDEX ix_sessions_matched_workout
    ON scheduled_sessions (matched_workout_id)
    WHERE matched_workout_id IS NOT NULL;
```

Notes:
- `ix_sessions_match_candidates` is **partial** on `status = 'PLANNED'` — the matcher only ever
  considers un-fulfilled sessions, so the index stays small as completed/missed history grows. It
  directly serves candidate selection (sport + date-window).
- `match_is_manual` enforces invariant 2 (manual override sticky) — the matcher skips sessions
  already manually linked.
- `compliance_score` stores the overall match quality; per-target deltas live in the compliance
  detail (below or computed on read — see fetch plans).
- `ix_sessions_matched_workout` lets a `WorkoutUpdated`/`WorkoutDeleted` find the session to
  re-evaluate without scanning.

### planned_targets

```sql
CREATE TABLE planned_targets (
    id          UUID PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES scheduled_sessions(id) ON DELETE CASCADE,  -- intra-aggregate FK
    type        TEXT NOT NULL,                -- DISTANCE|DURATION|PACE|POWER|TSS
    value       DOUBLE PRECISION NOT NULL,    -- metres|seconds|sec-per-km|watts|TSS points
    unit        TEXT NOT NULL                 -- human-readable label
);

CREATE INDEX ix_targets_session ON planned_targets (session_id);
```

A session has 1..* targets. `type = TSS` stores the **planned** TSS (Planning-owned; not read from
Analytics — see [domain-model.md](domain-model.md)). The `ix_targets_session` index is the FK-side
index Postgres does not auto-create, needed for the entity-graph child fetch and the cascade
delete.

### compliance_deltas (optional detail — per-target match result)

```sql
CREATE TABLE compliance_deltas (
    id           UUID PRIMARY KEY,
    session_id   UUID NOT NULL REFERENCES scheduled_sessions(id) ON DELETE CASCADE,
    target_type  TEXT NOT NULL,               -- which PlannedTarget this evaluates
    planned      DOUBLE PRECISION NOT NULL,
    actual       DOUBLE PRECISION NOT NULL,
    delta_pct    DOUBLE PRECISION NOT NULL    -- (actual-planned)/planned
);

CREATE INDEX ix_compliance_session ON compliance_deltas (session_id);
```

Written when a match is established; one row per evaluated target ("planned 10 km, did 9.4 km,
−6%"). `target_type = TSS` rows are **not** produced in MVP (actual TSS is not read); they become
possible if the post-MVP TSS-accurate compliance enhancement is added. Kept as a separate table so
the per-target breakdown is queryable without parsing a JSON blob.

## Fetch plans

- **Calendar / plan detail** — entity graph loads `plan` + `sessions` + each session's `targets`.
  As in Catalog, the two collection levels (sessions, then targets) are fetched as **separate
  batched selects**, not one Cartesian join, avoiding `MultipleBagFetchException`. `@BatchSize`
  collapses the per-session target fetches.
- **Plan list** — a **projection** (`PlanSummary` record: id, name, dates, status, session count),
  no aggregate hydration — same feed-vs-detail split as Catalog.
- **Match candidate selection** — a direct query on `ix_sessions_match_candidates`, returning the
  small candidate set; scoring happens in the service, not SQL.

## Migrations

Flyway per-BC, under the Planning module:
1. `V1__planning_create_plans.sql`
2. `V2__planning_create_scheduled_sessions.sql` — table + candidate/calendar/reverse-match indexes
3. `V3__planning_create_planned_targets.sql`
4. `V4__planning_create_compliance_deltas.sql`

Adding a `TargetType` or `SessionStatus` needs no migration (`TEXT`).

## What is intentionally NOT here

- **No copy of workout data.** A matched workout is referenced by `matched_workout_id`; its
  distance/duration/etc. are read from Catalog when needed, not duplicated here. Only the
  *compliance result* (deltas) is stored.
- **No actual-TSS column.** Actual TSS is an Analytics metric; MVP compliance does not read it, so
  it is not stored (see the decoupling).
- **No cross-BC foreign keys.** `user_id`, `matched_workout_id` are plain columns.
- **No generated-plan / template tables.** AI- or template-generated plans are post-MVP; MVP plans
  are user-authored.

## Next documents in this BC

- [events.md](events.md) — published `ScheduledSessionDue/SessionCompleted/SessionMissed`; consumed `WorkoutCreated/Updated/Deleted`
- [api.md](api.md) — plan CRUD, calendar, manual match override
- Sequence diagram: `diagrams/sequence/planned-vs-actual-matching.md`
- [domain-model.md](domain-model.md) — already written
