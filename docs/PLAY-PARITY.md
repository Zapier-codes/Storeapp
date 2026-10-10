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

---

## 3. Porting notes

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
