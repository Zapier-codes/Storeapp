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
- [ ] 1.a.i.zo — `ZealotClient`: new object, same shape as `GitLabClient`/`CodebergClient` (own `service`/fetch functions, own base URL config), fetching `index.json` + `index.json.sig` from `ZEALOT_CATALOG_INDEX_BASE_URL`. Native port of D-Store's `lib/sources/zealot.ts` fetch step — same URLs, same "index is never its own trust anchor" posture.
- [ ] 1.a.ii.zi — Signature/trust verification: Ed25519 detached-signature check over the raw index bytes against the pinned key, schema-version check, anti-rollback (`sequence`), `expires_at` check, last-good-index cache on failed fetch. Native port of D-Store's `lib/sources/zealot-trust.ts` — same pinned key (`dbfa9202d7894165`), same rotation-window posture (accept any key in the pinned list, never a single-entry replace). **This is new capability for the app as a whole** — no existing source has this, so it isn't "extending" anything, it's the first of its kind here.
- [ ] 1.a.ii.zo — `ZealotEntry.toUnifiedRepo()`: converter from Zealot's verified index entries into `GitHubRepo`, same pattern as the other six converters.
- [ ] 1.a.iii.zi — Prepend-first merge: Zealot's verified results go first — home shelves, `openSourceBrowse`'s merged list, category browsing, and search results — implemented as a new conditional branch specific to `AppSource.ZEALOT` (prepend, never sorted in), not a `stargazers_count` boost. Confirmed against the actual merge-by-stars logic in `openSourceBrowse` this session; that logic has no source-priority concept today, so this is genuinely new branching, not a parameter tweak. *(maps to D-Store's `6.a.iii.zo`)*
- [ ] 1.a.iii.zo — Register the Zealot adapter in whichever source list `UpdateCheckWorker` already iterates for update checks — no change to WorkManager itself. *(maps to D-Store's `6.a.ii.zo`)*
- [ ] 1.a.iv.zi — Route Zealot-sourced installs through the trust gate built in Track b (`InstallGateway`), gated on `1.a.ii.zi`'s verification passing. **Held until `1.b.i.zo` lands** — there is no gateway to route through yet. *(maps to D-Store's `6.a.ii.zi`)*

### Track b — Install/verify foundation
*(not cross-repo-tracked in D-Store's file; this is purely this repo's own groundwork, needed before `1.a.iv.zi` is buildable)*

- [ ] 1.b.i.zi — `Verifier`: SHA-256 checksum check (downloaded bytes vs. source-claimed checksum) + signing-certificate fingerprint check (`PackageManager.getPackageArchiveInfo` + `signingInfo`, vs. source-claimed fingerprint), independent checks, sealed result type (`Trusted`/`SignatureMismatch`/`ChecksumMismatch`/...) rather than a boolean.
- [ ] 1.b.i.zo — `InstallGateway`: single entry point wrapping the two existing `FileProvider`+`ACTION_VIEW` install call sites in `AppData.kt` (currently duplicated) behind one function that runs `Verifier` first. **Explicitly not** a `PackageInstaller.Session` rewrite and **explicitly not** Shizuku — both considered and deliberately out of scope (see decisions below).

---

## 2. Decisions on record

- **No Shizuku.** The existing `ACTION_VIEW`-based install path already works on unrooted devices with no extra permissions. Shizuku would only buy a silent (no-tap) install — a real feature with real setup friction (separate Shizuku/Sui install, pairing, per-app grant) that nothing in this program currently requires. Not built; revisit only if silent/MDM-style install becomes an actual product requirement, as its own separately-scoped leaf.
- **No `PackageInstaller.Session` rewrite.** The current `ACTION_VIEW` install path works; rewriting it buys marginal UX for real risk. `1.b.i.zo` wraps the existing calls, it doesn't replace them.
- **No CDN migration.** The six existing sources' data (hardcoded CDN base + five live clients) stays exactly as-is, untouched, not re-hosted under Zapier-codes infrastructure. See Section 0.

---

## 3. Handoff process

Same as D-Store's: on finishing a leaf, flip its `[ ]` to `[x]`, note what shipped and what's flagged forward, and if a leaf can't finish in one session mark it `[~]` with a note on what's left rather than committing partial work as done.
