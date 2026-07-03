# CROSS-APP.md — inter-app communication in the suite

Canonical record of how the suite's apps talk to each other: the rules every surface follows,
what exists, what's approved to build, and what was deliberately rejected. Reviewed and the
roadmap below **approved by the owner 2026-07-03**. Lives in the Dragonfly repo because
cross-app architecture is hub territory (see also [BROKER.md](BROKER.md) for config/identity).

## Design rules (every cross-app surface follows these — no exceptions without updating this file)

1. **Point-to-point HTTPS between backends.** No shared database (rejected early, stays
   rejected), no message bus (at household scale it's résumé-driven architecture).
2. **Separate auth surface from user sessions.** Cross-app calls never use an app's own user
   tokens. Today: HS256 JWT signed with the shared `CROSS_APP_SECRET`, `type=cross_app`,
   carrying the user's **email** — the only stable identity across independent `users` tables.
   Reference implementation: Spotter `security.get_cross_app_user`.
3. **Disabled-when-unset.** An app with no secret/flag configured returns 401/404 for the
   surface; cross-app features degrade to absence, never to errors in the consumer's UX.
4. **Read-only by strong preference.** Writes only where the user explicitly acts in the
   consumer app (e.g. Cookbook's "log to Plate" button). No background cross-app writes.
5. **Rate-limited and additive.** Each surface gets a slowapi limit; breaking changes to a
   cross-app contract require coordinated releases of provider and consumer(s) in one window.
6. **Nutrition is the shared language, Plate is the hub of it.** App-to-app links route
   through Plate where the payload is food/macros; Spotter↔Cookbook direct links are a
   non-goal (topology creep toward N×N).

## Surfaces in production (2026-07-03)

| Provider | Surface | Consumer | Purpose |
|---|---|---|---|
| Spotter | `GET /workouts?date=` | Plate | Training-day intake bump + coach framing |
| Plate | `GET /recipes/export` | Cookbook | One-time/ongoing recipe import |
| Plate | `POST /cross-app/resolve-foods` | Cookbook | Ingredient → nutrition resolution |
| Plate | `POST /cross-app/log-recipe` | Cookbook | "I made this" → Plate food diary (user-initiated write) |
| dragonfly-id | OIDC (`/authorize`, `/token`, JWKS) + each app's `POST /auth/suite` | all app clients/servers | Suite SSO (BROKER.md Phase 2) |
| Dragonfly app | `SuiteConfigProvider` ContentProvider | sibling Android apps | Hub-managed server URLs (BROKER.md Phase 1) |

## Approved roadmap (owner-approved 2026-07-03, in priority order)

### 1. Bodyweight single source of truth — Plate becomes the weight authority
The one live data-integrity problem: Spotter (`BodyMetric`) and Plate both keep bodyweight
diaries for the same person. Plate owns the trend engine (`nutrition/trend.py`) and the planned
adaptive-TDEE correction — half the weigh-ins landing in Spotter starves that math.
- Plate adds `GET /cross-app/weight?start=&end=` and `POST /cross-app/weight`
  (email-identified, same auth pattern; POST is user-initiated from Spotter's log action).
- Spotter's bodyweight logging **writes through** to Plate (local `BodyMetric` kept as
  offline-tolerant cache/fallback, synced like set logs); Spotter's charts read the merged
  series. Feature-flagged on `PLATE_BASE_URL` + secret being set.
- Canonical unit is **kg** on the wire (metric-canonical rule, both apps convert at edges).

### 2. Cookbook meal plan → Plate coach & targets
Cookbook knows tonight's planned dinner and its approximate macros; Plate's coach currently
reasons blind to it.
- Cookbook adds `GET /cross-app/plan?date=` (or `start=/end=`) returning planned entries with
  per-recipe nutrition (reusing its existing Plate-resolved data; free-text notes come back
  as name-only).
- Plate's target/coach context gains "planned dinner ≈ N kcal" ("you have 900 left, chili is
  ~700, snack budget 200"). Trusted-context injection only — the coach never auto-logs it.

### 3. Plate remaining macros → Cookbook suggestion ranking
Inverse of #2, second phase. Plate adds `GET /cross-app/remaining?date=` (kcal + macros left
today); Cookbook's pantry/"what can I make" suggestions get a fit badge or rank boost for
recipes that fit. Pure ranking input — suggestions must keep working with the flag unset
(design rule 3).

### 4. Range reads for the weekly digest
The suite digest (host roadmap Tier 3; natural home = a job in Dragonfly `server/`) needs
"what happened this week" from each app:
- Spotter: extend `/workouts` with `?start=&end=` (additive; single-date form stays).
- Plate: `GET /log/summary?start=&end=` already exists — expose it on the cross-app auth
  surface.
- Cookbook: add a cook-events/plan range read.
Build the range endpoints opportunistically (they're cheap); the digest job itself is a later,
separate deliverable and becomes a pure consumer.

## Infrastructure prerequisites (do alongside items 1–4, not after)

- **Service tokens replace `CROSS_APP_SECRET`.** Every surface above raises the blast radius
  of the one shared symmetric secret (already a three-repo flag-day to rotate). Target: the
  provider validates RS256 tokens from dragonfly-id (aud=`cross-app` or reuse `suite`) via the
  JWKS trust each server already has for SSO. New surfaces should be written against a small
  shared verify helper so the swap is one function, not N endpoints. Until the swap, new
  surfaces may launch on `CROSS_APP_SECRET` but must not deepen its coupling (no new claims).
- **Contract fixtures.** Cross-app contracts are currently tested only against hand-written
  mocks — nothing detects drift between Plate's real `/recipes/export` and Cookbook's mock of
  it. Rule going forward: the **provider** repo commits response-shape fixtures (sample JSON)
  for each cross-app surface under `server/tests/contracts/`; **consumers** copy those files
  verbatim and build their mocks from them. A contract change = a fixture diff in both repos =
  visible in review.
- **Shared preferences via the Dragonfly broker.** Extend `SuiteConfigProvider` beyond server
  URLs with a `unit_system` key (imperial/metric) so the units preference is set once in the
  hub. Same signature-permission read path; apps keep their local setting as override.
- **Sibling deep links.** Apps share a signing key and already carry `<queries>` for the hub;
  add sibling package queries + intent links where a cross-app reference exists (Plate diary
  entry → Cookbook recipe; Spotter rest-day card → Cookbook meal plan). Polish tier — after
  1–3 ship.

## Non-goals (decided 2026-07-03 — reverse deliberately, not by drift)

- **Spotter ↔ Cookbook direct integration** — route through Plate (design rule 6).
- **Hawksnest/HA ↔ suite data links** — different trust domain (it moves door locks); the
  future shared **push channel** (ntfy) is acceptable as dumb transport both worlds publish
  into without knowing each other.
- **kidbot ↔ anything** — isolation is a privacy feature of a child's device. The shared LM
  Studio instance is an operational coupling, documented, not an API.
- **Event bus / message queue / shared DB** — wrong scale, rejected.
