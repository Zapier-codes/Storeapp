# Play Store / Play Console parity

A cross-check of what the Google Play **Store** (the consumer app) and the Play
**Console** (the publisher's web console) offer, against what this catalogue has.
Every row says where the capability lives, or exactly why it is out of scope for a
multi-source open-source catalogue — so the next session can port any of it without
re-deriving the decision.

Status legend

| Mark | Meaning |
| --- | --- |
| **have** | Implemented here, on real data. |
| **derived** | Play shows a hand-curated field; we show the honest equivalent computed from data we already hold. |
| **port** | Buildable later; a porting note says from where. |
| **n/a** | No honest equivalent exists for this catalogue (a paid listing, a bidding ad system). Not built on purpose. |

Source files: `PlayModels.kt` (models + derivation), `PlaySurfaces.kt` (Compose
UI), `PlayHome.kt` (home carousel/collection tiles), `AppData.kt` (state +
ViewModel), `AppComponents.kt` (detail/search wiring).

---

## 1. Play Store — consumer capabilities

| Play capability | Status | Where / note |
| --- | --- | --- |
| Listing header (icon, title, developer, installed badge) | have | `AppDetailScreen` header (pre-existing). |
| Short + full description, screenshots, changelog | have | pre-existing (`fetchScreenshots`, README render). |
| Rating line — stars + count | derived | `PlayRatingRow`. Publisher `listing.json` supplies a real rating when present; otherwise the row reads "Not yet rated" rather than a fake zero. |
| Install / update / uninstall | have | pre-existing install path. |
| **Per-app auto-update** toggle | have | `PlayLifecycleControls`; opt-out persisted (`PreferencesManager.loadAutoUpdateOptOut`) and honoured by `updateAll()` and `UpdateCheckWorker`. |
| **Pre-register / early access** | have | `PlayLifecycleControls`; stores the reserved version (`togglePreRegister`). Play's staged rollout is n/a for an open-source catalogue — the honest behaviour is to remember the intent and surface the release when it lands. |
| "You might also like" rail | derived | `SimilarAppsRail` + `similarAppsFor`, scored by language / shared description words / source. Shuffled rows were rejected — an unrelated rail is worse than none. |
| Badges (Editors' Choice, Trending, Updated, No ads, Open source) | derived | `badgesFor` + `ListingBadgesRow`. The ones we can derive honestly (updated ≤30d, ≥10k stars, open source by source) are computed; the curated ones come from `listing.json`. |
| **Data Safety** panel | have | `DataSafetyPanel`. Permissions read live from the installed APK (`readInstalledPermissions`); collect/share claims from `listing.json`. With neither, it explains itself. |
| **Ratings & reviews** (with developer replies) | have | `ReviewsPanel`, from `listing.json`. Empty state explains the catalogue rates by trust signals instead. |
| **"More by <developer>" page** | have | `openDeveloper` + the `SeeAllScreen` developer page. Built from the in-memory catalogue (instant, offline). |
| Search sort (relevance/stars/updated/name/size) | have | `SearchSortFilterRow` + `applySearchView`. |
| Search filters (installed, has-APK, min stars) | have | `SearchSortFilterRow` + `SearchFilters`. |
| "Watch trailer" (video) | derived | `trailerUrlFor` pulls a real YouTube/Vimeo link from the README/description; the button only appears when one exists. |
| Content rating (age) | port | Needs a publisher form (`listing.json.contentRating` already modelled). The panel is intentionally not shown until a value exists — a guessed rating is a safety claim. |
| In-app purchases / price | n/a | This catalogue aggregates open-source releases; there are no paid listings to model. |
| Play Pass / Points / gift cards / subscriptions | n/a | Account-and-billing features of Google's own store. |
| Ad-supported flags | port | Same as content rating: modelled on `AppListingMeta`, shown only when supplied. |
| Download/Wi-Fi-only setting | have | `PreferencesManager.loadWifiOnlyDownloads` + `setWifiOnlyDownloads`. |
| Device compatibility filter ("works on your device") | port | We already read `minSdk` from an installed APK; wiring it as a search facet needs the catalogue to carry `minSdk` per app (add to the Zealot/D-Store index). |
| **My apps & games / Updates** (installed list, update-all) | have | Classic's `INSTALLED` tab and the Expressive Updates screen; `updateAll()` + `UpdateScanEngine`. Play's hamburger "My apps & games" maps to the same list. |
| **Play Protect** ("scanning for harmful apps") | derived | The client verifies the SHA-256 checksum and signer continuity on every install (`Verifier`/`VerifierPolicy`) and refuses a mismatch or a downgrade. That is the honest analogue of Play Protect for a signed multi-source catalogue; there is no cloud "scan" and none is wanted. |
| **Notifications / push** | have | `PushRegistrar` + FCM push; per-event notification preferences. |
| **Account / sign-in** | n/a (by design) | This store has no account; installs and settings are device-local. Recorded so it is not re-opened as a gap. |
| **Library / order history / purchase history** | n/a | Nothing is bought in an open-source catalogue, so there is no purchase history to show. |
| **Subscriptions / in-app purchases / price** | n/a | No billing; the catalogue carries free releases only. |
| **Redeem / offers / Play Points / gift cards / Google One** | n/a | Google's own account-and-rewards programmes; no honest equivalent. |
| **Payment methods** | n/a | No user billing. |
| **Parental controls / content filtering / family** | port | Could map to `content_rating` + an age filter, but the catalogue only carries a rating when the publisher supplies one, so the filter is gated on publisher data (same gate as the content-rating row). |
| **Settings** (per-app auto-update, Wi-Fi-only, theme, language, backup) | have | `PlayLifecycleControls` + `PreferencesManager` + the Settings screen (Classic) / Settings tab (Expressive). |
| **Country / language preference** | have (partial) | `language` setting plus per-shell label resolution. |

## 2. Play Console — publisher capabilities

The Console is a web product; the parts that make sense in a *client* are the
catalogue-side ones. The rest are honest **n/a** and are listed so the next session
does not re-open them.

| Console capability | Status | Where / note |
| --- | --- | --- |
| Publish release (upload AAB/APK, release notes) | have (Zealot) | Zealot's Rails console publishes; this client consumes the signed index. |
| **Listing metadata** (title, short/long description, graphics) | have | This catalogue's `listing.json` is the format these fields arrive in; `fetchListingMeta` reads it. |
| **Ratings & reviews + developer reply** | have | `ReviewItem.devReply` + `ReviewsPanel`; reply authoring is a Console-side action, see below. |
| **Data safety form** | have | `DataSafetyInfo`, surfaced by `DataSafetyPanel`. |
| **Content rating questionnaire** | port | `AppListingMeta.contentRating` is the field; the questionnaire itself is a publisher web-form (out of a client's scope). |
| Staged rollout / percentage rollout | n/a | No staged channel in an open-source release feed. Pre-register covers the user-visible half. |
| Testing tracks (internal / closed / open) | port | Zealot could expose a `track` on the release entry; the client would then filter. Not modelled yet. |
| App bundles (AAB) + dynamic delivery | n/a | Sources here publish APKs; the installer verifies APKs. |
| Crash / ANR / vitals dashboards | n/a | No telemetry in this app, by design (privacy). |
| Store listing experiments, store performance | n/a | Publisher analytics, not a client feature. |
| **Developer page ownership** | have | `openDeveloper` groups the catalogue by owner login. |
| Play App Signing / key rotation | n/a | Out of scope: multi-source, and the app verifies signer continuity on install instead. |
| **Billing / payments (in-app purchases, subscriptions, pricing)** | n/a (client) | The catalogue is open-source; there is nothing to price or bill in the client. See Zealot's handover for the one money path the program does have (a publisher pays for a paid *store listing* through B-Pay-backend) — a Console-side revenue feature, not a client one. |
| **Revenue / earnings reporting, financial reports, payouts** | n/a (client) | Follows from the row above: no user billing means no earnings to report in an app. |
| **App content / policy declarations** (Data safety, Content rating, Target audience, Ads, News) | have / port | Data safety `have` (`DataSafetyInfo` + `DataSafetyPanel`); content rating and ads are modelled and shown only when supplied; target audience and news app declarations are `port` (publisher forms). |

---

## 3. Porting notes

> **Gap inventory (operator-directed 2026-10-10):** this table was extended with the Play Store **hamburger menu** surfaces and the Play Console **billing / revenue** surfaces the operator named. The finding, kept here so the next session does not re-derive it: the client's hamburger features that have an honest equivalent are already built (My apps & games = the installed list + Updates; notifications = FCM push; Play Protect = the on-install verify gate; settings); the ones that are Google-account or billing features (sign-in, subscriptions, library/order history, redeem/offers/Points, payment methods) are `n/a` on purpose because this catalogue has no accounts and no billing. The revenue half belongs to Zealot's console, not this client. See Zealot's `handover.md` for that side.


The Play-parity work is split so the web storefront (`D-Store`) can carry the same
surfaces. To port:

1. **Models + derivation** — `PlayModels.kt` is dependency-free Kotlin (no Compose,
   no Android except `readInstalledPermissions`, which the web front-end replaces
   with the manifest the build already knows). Port `badgesFor`, `similarAppsFor`,
   `trailerUrlFor`, `installsLabelFor`, `applySearchView` as pure functions.
2. **UI** — `PlaySurfaces.kt` and `PlayHome.kt` are the Compose renderings. The web
   storefront's equivalents live in `D-Store` under `components/` (see that repo's
   `docs/PLAY-PARITY.md`, which mirrors this table with web status).
3. **State** — `UiState` fields (`listingMeta`, `autoUpdateOptOut`,
   `wifiOnlyDownloads`, `preRegistered`, `searchSort`, `searchFilters`,
   `developerLogin`) and the `AppViewModel` methods are the contract a port must
   re-implement; the names are kept Play-shaped on purpose.
4. **Data source** — the optional `listing.json` is the one new input. It sits next
   to the release metadata:
   `…/data/releases/github/{owner}/{name}.listing.json`. Absent is the normal case
   and must never be an error.


---

## 4. Full cross-check: Play Store against Appstore (operator-directed 2026-10-10; docs only, nothing built or run)

Sections 1 to 3 above are the first pass. This section adds the embedded platform tools and the consumer / enterprise tags, and records the operating principles the operator set. The Console half is Zealot's `docs/PLAY-PARITY.md`; the web mirror is D-Store's.

## Operating principles for closing every gap *(operator directive, 2026-10-10)*

1. **Human steps are automated.** Anywhere Play puts a person in the loop (app review, policy decisions, content-rating questionnaires, appeals triage, support routing), this program builds an automated decision instead: a machine verdict with machine-readable reasons (pass, flag, reject), recorded on the release. A person is only the exception path (an appeal, or a verdict the automation marks as high risk), never a queue that publishing waits on. Existing rules stand: an update to an app that already has a previous version is never held (Zealot Task 48/49).
2. **Reviews are anonymous. No accounts, ever.** The intended design (not built; its own task, cut by the TSF before code): a device-bound pseudonymous key made on the phone (Android Keystore), one editable review per key per app, a proof-of-work challenge instead of a captcha service, rate limits per key and per network, automated moderation, and a visible "verified install" mark when the review came from the Appstore client with proof that the reviewed version was installed. The website accepts the same review with the proof-of-work token and no install mark. Developer replies are public. There is no sign-in, no email and no profile anywhere in this path.
3. **Everything else follows the industry-standard approach**, as the earlier decision records (D43-n, 47h and the rest) already do, and keeps this program's own intended approaches where a handover has recorded one (additive-only sources, signed catalog index, org signing key, CI signs everything, no telemetry by default).
4. **Reuse before writing.** Most missing areas already exist as open-source parts that can be assembled. The reuse map below lists them with a mark: **✓** = the project's own page was read in a search on 2026-10-10; **◇** = from the author's knowledge, not checked, so check licence and maintenance before adopting. Nothing in the map is adopted yet.
5. **Unofficial routes carry a stated risk.** A route that depends on a reverse-engineered or unpublished interface is listed with that risk, and is never the only path to a feature.


Key: ✅ have · ◐ partial · ❌ missing · ➖ not applicable by design · ❓ not confirmed in any handover. **C** = consumer, **E** = enterprise.

| Feature, including embedded platform tools | Tag | Play | Appstore |
|---|---|---|---|
| Browse, search, details, screenshots, changelog | C | ✅ | ✅ |
| Install, update, uninstall through PackageInstaller sessions | C | ✅ | ✅ |
| Per-app auto-update, Wi-Fi-only downloads | C | ✅ | ✅ |
| Background update checks for every source | C | ✅ | ◐ (Zealot path only; the other six sources have the gap) |
| Update ownership (Android 14) | C | ✅ | ◐ (best effort, not yet verified on a device) |
| Delta or patch updates | C | ✅ | ❌ |
| Split APKs and dynamic delivery (config splits per device) | C | ✅ | ❌ (serves a universal APK) |
| Asset packs (install-time, fast-follow, on-demand) | C | ✅ | ❌ in the client |
| Resumable downloads | C | ✅ | ◐ (website proxy yes, client ❓) |
| Download queue: pause, cancel, retry, storage check | C | ✅ | ❌ |
| Pre-register / early access | C | ✅ | ✅ |
| Read ratings and reviews | C | ✅ | ✅ |
| Write reviews, helpful votes, report a review, filter by device | C | ✅ | ❌ (anonymous design, principle 2) |
| Data Safety panel | C | ✅ | ✅ |
| Content rating, age filter, parental controls | C | ✅ | ❌ (port; waits on Zealot's declarations) |
| Device compatibility filter ("works on your device") | C | ✅ | ❌ |
| Form-factor stores (tablet, TV, Wear, Auto) | C | ✅ | ❌ |
| Wishlist or save for later | C | ✅ | ❌ |
| Top charts, categories, Kids tab, editorial stories | C | ✅ | ◐ (collections and sponsored slots only) |
| Search suggestions, voice search | C | ✅ | ❓ |
| Verify checksum and signer on every install | C | ✅ | ✅ (the Play Protect analogue) |
| Cloud malware scanning, live threat detection | C | ✅ | ❌ (not matchable; replaced by Zealot's automated scan, see Zealot's doc) |
| Report or flag an app | C | ✅ | ◐ (website yes, client ❓) |
| Share an app | C | ✅ | ◐ |
| Push notifications | C | ✅ | ✅ |
| Archive unused apps, storage management | C | ✅ | ❌ |
| Android developer verification (a registered developer behind every installed app) | C/E | ✅ | ❌ |
| In-App Updates API (an app updates itself) | C | ✅ | ✅ (Zealot Task 47 updater) |
| In-App Review API | C | ✅ | ❌ (becomes the anonymous review path) |
| Install Referrer and deferred deep links | C | ✅ | ❌ |
| Play Integrity API (device and app attestation) | C/E | ✅ | ➖ (Android key attestation covers the anonymous-review need) |
| Play Billing, subscriptions, Play Pass, Points, gift cards | C | ✅ | ➖ |
| Accounts, library, order history, payment methods, Family Library | C | ✅ | ➖ |
| Instant apps, Play Games Services | C | ✅ | ➖ |
| Remote install to other devices | C | ✅ | ➖ (needs accounts) |
| Private apps for one organization | E | ✅ | ◐ (white-label tenants, per-tenant catalog) |
| Per-organization curated store layout | E | ✅ | ◐ (collections exist, tenant scope in progress) |
| MDM/EMM integration (Android Management API, Play EMM API) | E | ✅ | ❌ |
| Managed configurations (app restrictions pushed by IT) | E | ✅ | ❌ |
| Forced, silent or allow-listed installs through a device policy controller | E | ✅ | ❌ (Shizuku and root are off by default, blocked on decision (b)) |
| Work profile support | E | ✅ | ❌ |
| Admin approval of apps and permissions for an organization | E | ✅ | ❌ |


## Reuse map: client and storefront side (assemble, do not write from scratch)

**✓** = the project's own page was read in a search on 2026-10-10. **◇** = from the author's knowledge, not checked. Nothing here is adopted yet; each adoption is its own task, cut by the TSF before code. The Console-side half, with the same marks, is in Zealot's `docs/PLAY-PARITY.md`.

| Gap | Route | Mark | Note |
|---|---|---|---|
| Split APKs and dynamic delivery | **bundletool** builds device-specific APK sets from a bundle; Zealot's CI already runs bundletool for the universal APK. The client installs several APKs in one PackageInstaller session (the session code from leaf h.iii is the base). AppManager and SAI are open-source references for split installs | bundletool ✓ (used in Zealot CI per its handover); AppManager, SAI ◇ | Needs Zealot to publish per-device sets, or the client to send its device spec |
| Delta updates | **archive-patcher** client side: apply the patch to the installed base APK, check the result byte for byte, then SHA-256 and signer as today | ✓ android-developers.googleblog.com/2016/12/saving-data-reducing-the-size-of-app-updates-by-65-percent.html | Zealot makes the patch (see its doc) |
| Download queue | WorkManager with a resumable downloader; Aurora Store uses Fetch2 for the same job | Fetch2 ✓ (named in Aurora's page); WorkManager ◇ | |
| Managed configuration and silent installs for organizations | The client reads standard **app restrictions** (RestrictionsManager), so any device policy controller, Headwind MDM included, can configure it; Shizuku or Dhizuku for silent installs, behind `InstallGateway` and `Verifier`, off by default | RestrictionsManager ✓ (`enterprise/ManagedConfig.kt` + `app_restrictions.xml`); Headwind ✓; Shizuku, Dhizuku ◇ | Unblocks `j.x` once decision (b) is made. **Built 2026-10-10:** `ManagedConfig`/`ManagedConfigRules` parse the restrictions, `Settings.withManaged` folds them onto the person's own settings (unset key ⇒ person's choice; org set key ⇒ wins), org-hidden packages are enforced in `StoreViewModel.setHidden`/`clearHidden` and cannot be restored in-app, and Settings shows a "Managed by your organisation" card. `ManagedConfigTest` (13 cases). **Compiled + tested 2026-10-10 (this session):** the Android toolchain is present in the sandbox, so `:app:compileDefaultDebugKotlin`, `:app:assembleDefaultDebug` and the full `:app:testDefaultDebugUnitTest` task all pass (256 tests, 0 failures, 0 errors; `ManagedConfigTest` 13/13 green) — the "written, not compiled" hold on this row is cleared. |
| Anonymous reviews (client) | Key pair in the Android Keystore with **key attestation**, one editable review per key per app, proof of install of that version, proof-of-work token; the website uses **ALTCHA** | ALTCHA ✓ altcha.org/open-source-captcha/; Key Attestation ◇ | Principle 2 |
| Search suggestions | Postgres `pg_trgm` (D-Store already uses Supabase), or Meilisearch or Typesense; voice search is Android's SpeechRecognizer | ◇ | |
| Push without Google | UnifiedPush beside FCM | ◇ | For de-Googled devices |
| Reading F-Droid-format repos | **F-Droid index-v2** is a documented, signed format; Appstore already has F-Droid and IzzyOnDroid sources | ✓ f-droid.org/docs/All_our_APIs/ | |
| Update tracking across sources | **Obtainium** is the reference for multi-source update tracking; its app configurations are importable JSON ◇ | named in the verification coverage ✓; details ◇ | |
| Developer verification for the store itself | Register Appstore (`com.vythera.vyxelapps`) in Google's program, as Accrescent did | ✓ blog.accrescent.app/posts/android-developer-verification/ | Enforced from 2026-09-30 in Brazil, Indonesia, Singapore and Thailand; global in 2027 |
| Install referrer | No standard outside Play; design as its own task (a signed parameter on the download URL read on first launch) | ◇ | |

**Unofficial routes that exist, and how they are held.** Aurora Store (GPL-3.0) and gplaydl reach Google Play through a reverse-engineered interface and an anonymous-token dispenser; the `GPlayApi` library behind Aurora does the same from code. Their own pages warn it can break. **Operator decision (2026-10-10): used as dependencies, because they have not broken to date.** For Appstore the default is that Zealot's adapter makes the Play calls and the client reads the result from Zealot (one server, one rate, one place to switch it off). This app is AGPL-3.0, so GPL-3.0-or-later code may legally be linked into it, but a client that calls Play directly puts each phone's address and login in play, so that is not the default. Full rules: Zealot `docs/UNOFFICIAL-ROUTES.md`, section 1.1.
