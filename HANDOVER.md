# Storeapp — HANDOVER.md
*Read this file first, every session. Same convention as D-Store's `HANDOVER.md` (`github.com/Zapier-codes/D-Store`) — a leaf is one task, addressed by full path (e.g. `1.a.i.zi`), status markers `[ ]` open / `[~]` in progress / `[x]` done / `[-]` superseded.*

---

## 0. Cross-repo context

This repo is the on-device client in the same program as `D-Store` (public storefront, Next.js) and `zealot` (the Console — Rails app, org signing, staged rollout, signed catalog index). D-Store's own `HANDOVER.md` tracks the cross-repo leaves below for visibility only, under its `6.a` track — those leaf IDs are referenced here so status stays addressable from either file, but *this* file is where the actual build checklist and status live.

**Codebase reality, confirmed by reading the repo directly this session (not assumed):**
- `AppSource` enum (`AppData.kt`) already has seven sources: GitHub, GitLab, Codeberg, F-Droid, IzzyOnDroid, Flathub, Winget.
- `GitHubRepo` is the unified app model every source converts into (`toUnifiedRepo()` / `AppEntry.toGitHubRepo()`).
- `MetadataClient`/`MetadataManager` is a generic CDN-index client, hardcoded to the *original Vyxel Apps author's* GitHub Pages index (`nikhilkain.github.io/appstore-metadata`) — not Zapier-codes-controlled, and per the operator decision below, staying that way.
- Installs today go through `FileProvider` + `Intent.ACTION_VIEW` (`AppData.kt`, two call sites) — not a `PackageInstaller.Session`. This works and is not being replaced (see Track b).
- **No source verifies anything today** — no signature check, no checksum check, on any of the seven existing sources.

**Operator decision (scope, recorded here and in D-Store's `HANDOVER.md`):** additive-only. The six existing sources' data pipeline (hardcoded CDN base, all five live-API clients) is never touched. Zealot is new code alongside them. The one thing that is Zapier-codes-controlled and shared is Zealot's own publish target (`ZEALOT_CATALOG_INDEX_BASE_URL` — the same GitHub Pages target D-Store's `lib/sources/zealot.ts` reads); this repo's `ZealotClient` reads that same target directly, independent of the website, never proxying through it.

---

## 1. Task Hierarchy

### Track a — Zealot source integration
*(maps to D-Store `HANDOVER.md` leaves `6.a.i.zo` / `6.a.ii.zi` / `6.a.ii.zo` / `6.a.iii.zo`, tracked there for visibility only)*

- [x] 1.a.i.zi — Add `AppSource.ZEALOT` enum entry (label, color) and its `cdnKey`/routing stub in `openSourceBrowse`'s `when` block. No data wiring yet — this leaf only makes the source addressable in the existing enum-driven UI (source filter chips, browse routing).
  - **Done note:** `AppSource.ZEALOT` added, color `0xFF3F6791` taken from Zealot's own `--bs-primary-bg` (`app/assets/stylesheets/_button.scss` in the `zealot` repo), not invented. `openSourceBrowse`'s `cdnKey` stub resolves to `"zealot"`, which `MetadataManager` has no CDN source for yet, so it safely no-ops to an empty browse rather than falling through to the generic GitHub topic-search branch — matches this leaf's "no data wiring yet" scope. Also added the required `AppSource.ZEALOT` case to `AppComponents.kt`'s one *exhaustive* `when (repo.source)` switch (no `else` branch — omitting it would have failed to compile), and to two more `when` switches that had an `else -> "GitHub"`/`"GitHub Developer"` fallback — left as `else` those would have silently mislabeled a Zealot app as GitHub, which is a correctness bug, not just an incomplete stub, so it was in scope to fix alongside adding the enum. **Deliberately left alone, flagged forward:** the home-screen `SourcesRow` shelf (`HomeScreen.kt`) takes explicit per-source count params (`gitlabCount`, `fdroidCount`, etc.) and `state.zealotApps` doesn't exist — wiring a Zealot chip/shelf into that composable needs real data and belongs to `1.a.i.zo`/`1.a.ii.zo` onward, not this leaf. **Not verified by compiling** — no Android SDK/Gradle build attempted in-sandbox (network here doesn't reach Google's Maven), reviewed by direct code inspection instead; first session with real build tooling should confirm a clean build before trusting this leaf fully.
- [x] 1.a.i.zo — `ZealotClient`: new object, same shape as `GitLabClient`/`CodebergClient` (own `service`/fetch functions, own base URL config), fetching `index.json` + `index.json.sig` from `ZEALOT_CATALOG_INDEX_BASE_URL`. Native port of D-Store's `lib/sources/zealot.ts` fetch step — same URLs, same "index is never its own trust anchor" posture.
  - **Done note:** `ZealotClient` added to `AppData.kt`, directly after `IzzyOnDroidClient`. Read D-Store's actual `lib/sources/zealot.ts` (`fetchLiveIndex`) this session rather than guessing at the shape — confirmed there is no committed real base URL in either repo; D-Store reads it from `process.env.ZEALOT_CATALOG_INDEX_BASE_URL` at runtime (Render-side secret, per that file's own header comment), unset meaning "no live source configured yet," not an error. Ported that same posture as a settable `@Volatile var baseUrl: String = ""` (same mutable-config-on-an-object pattern `RetrofitClient.authToken` already uses in this file), blank meaning the same thing. `fetchIndex()` fetches `index.json` + `index.json.sig` in parallel (`kotlinx.coroutines.async`, matching the TS original's `Promise.all`), off `Dispatchers.IO`, and returns a raw `RawIndexFetch(indexText, signatureText)` or `null` — collapsing a blank `baseUrl`, a non-2xx response on either file, or any network exception to the same "nothing fetched" outcome the TS original uses, so the (not-yet-built) caller has one failure shape to handle. **Deliberately not built here, on scope:** no signature/schema/`expires_at`/anti-rollback verification (confirmed by reading `zealot-trust.ts`'s own header, which frames that check as its own leaf, `1.a.ii.zi`, precisely so nothing here can be mistaken for a trust decision) and no `RawIndex`/`RawApp` parsing or `GitHubRepo` conversion (`1.a.ii.zo`) — this object only returns two raw strings. No caller wired in yet since both downstream leaves it feeds are still open. **Not verified by compiling** — same in-sandbox limitation every other leaf here notes (no Google Maven access); reviewed by direct code inspection against the TS original instead, including a brace/paren sanity check confined to my own diff (the file's pre-existing paren count was already unbalanced from comment text, confirmed via `git stash` before attributing that to this change).
- [x] 1.a.ii.zi — Signature/trust verification: Ed25519 detached-signature check over the raw index bytes against the pinned key, schema-version check, anti-rollback (`sequence`), `expires_at` check, last-good-index cache on failed fetch. Native port of D-Store's `lib/sources/zealot-trust.ts` — same pinned key (`dbfa9202d7894165`), same rotation-window posture (accept any key in the pinned list, never a single-entry replace). **This is new capability for the app as a whole** — no existing source has this, so it isn't "extending" anything, it's the first of its kind here.
  - **Done note:** New file `api/ZealotTrust.kt` — pure port of `zealot-trust.ts`'s `PINNED_KEYS`/`SUPPORTED_SCHEMA_VERSION`/`verifySignature`/`IndexState`/`isRollback`/`isExpired`, no I/O, same pinned key (read directly from D-Store's file this session, not retyped from memory). **Real engineering decision made here, not a mechanical port:** confirmed against Android's own reference docs that `java.security.interfaces.EdECKey` (platform Ed25519 support) was only added at API 33, while this module's `minSdk` is 26 — so platform `java.security` crypto was ruled out (it would silently no-op below Android 13) in favor of BouncyCastle's `Ed25519Signer`/`Ed25519PublicKeyParameters`, added as `org.bouncycastle:bcprov-jdk18on:1.84` and used as raw algorithm classes, never registered as a JCA `Provider`. Recorded in "Decisions on record" below. `ZealotClient` (`AppData.kt`) extended with the fetch→verify→cache orchestration this leaf's own scope also covers (`validate`, `commitState`, `resolveVerifiedIndex`), backed by a dedicated `zealot_trust_state` `SharedPreferences` store (sequence + generated_at for anti-rollback, raw index/signature text as the last-good cache) — on any failure (unset `baseUrl`, network error, bad signature, wrong schema, expired, rolled back) falls back to the last index this device already verified, same deliberate "serve stale-but-verified" tradeoff `zealot.ts`'s own `createZealotSource` comment flags. Reads only a minimal `IndexEnvelope` (`schema_version`/`generated_at`/`sequence`/`expires_at`) — deliberately not the full `RawApp`/`apps` shape, so nothing here can be mistaken for having looked at actual app data before the signature check passes; `1.a.ii.zo` re-parses the same verified text for that. **Not verified by compiling** — same in-sandbox limitation as every other leaf (no Google Maven access here, so BouncyCastle itself couldn't be resolved/downloaded either); reviewed by direct code inspection against the TS original and BouncyCastle's documented raw-signer API instead.
- [ ] 1.a.ii.zo — `ZealotEntry.toUnifiedRepo()`: converter from Zealot's verified index entries into `GitHubRepo`, same pattern as the other six converters.
- [ ] 1.a.iii.zi — Prepend-first merge: Zealot's verified results go first — home shelves, `openSourceBrowse`'s merged list, category browsing, and search results — implemented as a new conditional branch specific to `AppSource.ZEALOT` (prepend, never sorted in), not a `stargazers_count` boost. Confirmed against the actual merge-by-stars logic in `openSourceBrowse` this session; that logic has no source-priority concept today, so this is genuinely new branching, not a parameter tweak. *(maps to D-Store's `6.a.iii.zo`)*
- [ ] 1.a.iii.zo — Register the Zealot adapter in whichever source list `UpdateCheckWorker` already iterates for update checks — no change to WorkManager itself. *(maps to D-Store's `6.a.ii.zo`)*
- [ ] 1.a.iv.zi — Route Zealot-sourced installs through the trust gate built in Track b (`InstallGateway`), gated on `1.a.ii.zi`'s verification passing. **Held until `1.b.i.zo` lands** — there is no gateway to route through yet. *(maps to D-Store's `6.a.ii.zi`)*

### Track b — Install/verify foundation
*(not cross-repo-tracked in D-Store's file; this is purely this repo's own groundwork, needed before `1.a.iv.zi` is buildable)*

- [ ] 1.b.i.zi — `Verifier`: SHA-256 checksum check (downloaded bytes vs. source-claimed checksum) + signing-certificate fingerprint check (`PackageManager.getPackageArchiveInfo` + `signingInfo`, vs. source-claimed fingerprint), independent checks, sealed result type (`Trusted`/`SignatureMismatch`/`ChecksumMismatch`/...) rather than a boolean.
- [ ] 1.b.i.zo — `InstallGateway`: single entry point wrapping the two existing `FileProvider`+`ACTION_VIEW` install call sites in `AppData.kt` (currently duplicated) behind one function that runs `Verifier` first. **Explicitly not** a `PackageInstaller.Session` rewrite and **explicitly not** Shizuku — both considered and deliberately out of scope (see decisions below).

### Track c — Multi-tenant restructuring
*(operator decision, recorded in Section 2 below; cross-repo — D-Store and Zealot legs are tracked here for visibility only, actual checklists live in their own `HANDOVER.md` files)*

- [x] 1.c.i.zi — Define the shared tenant-config schema/contract (fields, `schema_version`, additive-only-with-defaults policy) that Storeapp, D-Store, and Zealot all consume. Must land before any repo's implementation leaves below, since all three need to agree on shape first.
  - **Done note:** `spec/tenant-config-schema.md` + `spec/tenant-config.schema.json` (new). Fields: `schema_version` (fixed `1`), `tenant_id` (permanent, DNS-label-safe — same rule as the catalog index's `slug`, since D-Store/Zealot resolve tenants by subdomain), `sequence`/`expires_at` (anti-rollback/freshness, reusing the catalog index's own mechanism rather than a second one), `branding` (in-app display copy only — launcher name/icon stay out, see below), `cdn_base` (feeds `MetadataManager`, default tenant seeded with the exact existing hardcoded string), `catalog_index_base_url` (per-tenant equivalent of today's single `ZEALOT_CATALOG_INDEX_BASE_URL`, deliberately a separate field from `cdn_base` since one path is unsigned/legacy and the other is Ed25519-verified — conflating them would blur a real trust-boundary difference), `domains` (may be empty), `is_default_tenant` (informational only, not a trust signal). **Deliberately excluded, per the same "never its own trust anchor" rule the catalog index already follows:** packaging identity (`applicationId`, launcher name/icon — genuinely build-time-fixed by Android, owned by `1.c.i.zo`'s Gradle flavors, not this schema) and the verification public key itself (pinned client-side by `1.c.ii.zo`, never a field on the record it verifies). **Verified:** `tenant-config.schema.json` checked with `ajv`/`ajv-formats` (Node 22) against six fixtures — two valid (a default-tenant record matching today's exact hardcoded `cdn_base`; a fully-populated tenant) and four deliberately malformed (missing required fields, wrong `schema_version`, an unrecognized extra field, a `tenant_id` violating the DNS-label pattern) — all six resolved as expected. Three open decisions recorded in the doc, none blocking: canonical-copy location (Storeapp, referenced by commit elsewhere), who publishes a record (Zealot Task 37, not this slice), and whether `logo_sha256` should become a structural `dependentRequired` later. Unblocks `1.c.i.zo`, `1.c.ii.zi`, D-Store's `6.b.ii.zi`/`zo`, and Zealot's Task 37a.
- [ ] 1.c.i.zo — Storeapp: Gradle product flavors for **packaging identity** only (`applicationId`, launcher app name/icon) — one flavor per distributable tenant. Default flavor preserves the existing identity (`com.vythera.vyxelapps` / "Vyxel Apps") unchanged. Business logic, UI, and data layer stay one codebase, one path — flavors select packaging metadata only, not behavior.
- [ ] 1.c.ii.zi — Storeapp: `TenantConfig` runtime object, fetched on launch, cached locally with a TTL and a last-known-good fallback on failed fetch (reuse the caching pattern already used for the Zealot index — do not invent a second caching strategy). Replaces `MetadataManager`'s single hardcoded `cdnBase` with `TenantConfig.current.cdnBase`. The default tenant's `cdnBase` value is the existing hardcoded string (`https://nikhilkain.github.io/appstore-metadata`) — default install behavior does not change, it just moves from a compile-time constant to tenant-zero's config record.
- [ ] 1.c.ii.zo — Storeapp: Ed25519 signature/trust verification over the tenant-config payload itself — reuse `1.a.ii.zi`'s pinned-key/rotation-window/anti-rollback/`expires_at` posture rather than building a second trust mechanism, since tenant config is at least as security-sensitive as the catalog index (it tells the app which endpoints to trust). **Held until `1.a.ii.zi` lands**, since it's the thing being reused.
- [ ] 1.c.iii.zi — D-Store: domain/subdomain → tenant resolution at the Next.js middleware layer, backed by the shared tenant-config schema (`1.c.i.zi`). *(cross-repo, tracked here for visibility only — real checklist lives in D-Store's own `HANDOVER.md`)*
- [ ] 1.c.iii.zo — Zealot: same domain-based tenant resolution at the Rails middleware/Rack layer. *(cross-repo, tracked here for visibility only — real checklist lives in `zealot`'s own `HANDOVER.md`)*

---

## 2. Decisions on record

- **No Shizuku.** The existing `ACTION_VIEW`-based install path already works on unrooted devices with no extra permissions. Shizuku would only buy a silent (no-tap) install — a real feature with real setup friction (separate Shizuku/Sui install, pairing, per-app grant) that nothing in this program currently requires. Not built; revisit only if silent/MDM-style install becomes an actual product requirement, as its own separately-scoped leaf.
- **No `PackageInstaller.Session` rewrite.** The current `ACTION_VIEW` install path works; rewriting it buys marginal UX for real risk. `1.b.i.zo` wraps the existing calls, it doesn't replace them.
- **No CDN migration `[-]` superseded by Track c.** Originally recorded as: the six existing sources' data (hardcoded CDN base + five live clients) stays exactly as-is, untouched, not re-hosted under Zapier-codes infrastructure. **Superseded, this session:** `cdnBase` becomes one field of tenant config (`1.c.ii.zi`) so new tenants can specify their own metadata source without a code fork. This does **not** re-host or change the existing data — the default tenant's `cdnBase` is seeded with the exact existing hardcoded value, so current behavior for the existing install base is unchanged. Only the *mechanism* (compile-time constant → tenant-config field) changes.
- **BouncyCastle for Ed25519, not platform `java.security` crypto (`1.a.ii.zi`).** Android's `java.security.interfaces.EdECKey` (and the `Signature`/`KeyFactory` Ed25519 support behind it) was only added at API 33, confirmed against Android's own reference docs this session. This module's `minSdk` is 26 (Android 8.0) — relying on the platform provider would silently have no working Ed25519 on every device between API 26 and 32, which is unacceptable for a trust-anchor check specifically (a signature check that quietly no-ops on most of the install base isn't a check). `org.bouncycastle:bcprov-jdk18on`'s `Ed25519Signer`/`Ed25519PublicKeyParameters` are used directly as algorithm classes, never registered as a JCA `Provider` (no need to touch global provider precedence for one narrow check), and work identically across every API level this app supports.
- **Multi-tenant model: single runtime deployable, not fork-per-tenant.** One codebase, one build per repo; tenant identity resolved dynamically (domain-based for D-Store/Zealot, tenant-config-fetch-on-launch for Storeapp) rather than maintaining separate branded forks. Where Android's OS-level constraints make a field truly build-time-fixed (`applicationId`, launcher icon/name), those are isolated to Gradle product-flavor selection (`1.c.i.zo`) and nothing else — everything else (branding shown in-app, CDN/API endpoints, feature flags) is runtime tenant config. Tenant-config schema is explicitly versioned (`schema_version`) and additive-only-with-defaults, so an older client that hasn't updated yet never breaks on a newer config — same posture already committed to for the Zealot index's rotation window.

---

## 3. Handoff process

Same as D-Store's — but inlined here in full, not just referenced, because a
session (this one) previously read only the one-line summary that used to be
here, skipped opening D-Store's `HANDOVER.md` §3 for the actual mechanics, and
handed off wrong git commands as a result (plain `git diff` + `git apply`
instead of `git format-patch` + `git am`, and apply/push commands with no
concrete paths in them). Read this section itself from now on; don't defer to
D-Store's copy for the mechanics.

0. **Check upstream first:** `git fetch origin` and compare against
   `origin/main`. If origin has moved since the local clone/session was last
   synced, `git rebase origin/main` before starting the leaf and before
   generating any patch — a patch built against a stale base can fail to
   apply with `git am` even when its content is logically identical to what's
   already there.
1. **Do the one assigned leaf task** (Section 1 — nothing more).
2. **Update this file**: flip the completed leaf's `[ ]` to `[x]`, add a
   **Done note** on what shipped and what's flagged forward. If a leaf can't
   finish in one session, do not commit partial work as done — mark it `[~]`
   instead, with a note on exactly what's left, and commit with a `WIP:`
   prefix rather than the plain leaf-path message below.
3. **Commit** the code change and the `HANDOVER.md` update **together, in one
   commit**, with a message that starts with the leaf path:
   ```
   git add -A
   git commit -m "1.a.i.zo: add ZealotClient index fetch"
   ```
4. **Generate the patch** as a proper `git format-patch` output (an mbox-style
   file `git am` can apply) — **never** a plain `git diff`, which drops the
   commit metadata `git am` needs and isn't what step 6 below applies:
   ```
   git format-patch -1 HEAD -o patches/
   ```
   For a handoff spanning several commits, combine them into one file instead
   of handing off several: `git format-patch origin/main --stdout > patches/000X-<description>.patch`.
   Always hand off exactly one patch file, never more than one.
5. **Hand the patch file to the user** — never push directly unless
   explicitly told to. The patch is the deliverable that closes the session.
6. **Applying it** (next session, or the user, on their own machine) needs
   concrete paths, not bare filenames — state the actual repo path and the
   actual patch path/filename every time, e.g.:
   ```
   cd ~/Storeapp
   git am ~/storage/downloads/0001-1.a.i.zo-add-ZealotClient-index-fetch.patch
   git push origin main
   ```
   It's `git am` here, not `git apply` — `git apply` doesn't work on a
   `format-patch` file's mbox format the same way and won't carry the commit
   metadata through. This repo's default branch is `main` (not D-Store's
   `master`) — don't copy that detail across repos without checking.
