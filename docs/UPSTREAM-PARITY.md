# Upstream parity — the Vyxel Apps "Genesis" release (j.i)

Docs only. Nothing in this leaf is built or run; it is the file-by-file map that the
rest of Track j is cut from, written against the real diff rather than a description.

## Scope and how this was measured

* Base commit: `1698797` — the true merge base of `origin/main` and `upstream/main`
  (`git merge-base origin/main upstream/main`).
* Target: tag `v1.1.3` ("Genesis") on `upstream` = `github.com/NikhilKain/vyxel-apps`.
* Command: `git diff --name-status 1698797 v1.1.3` and
  `git diff --stat 1698797 v1.1.3`.
* Result: **54 files, 5,169 insertions, 612 deletions, 0 deletions of files.**
  33 added, 20 modified, 1 renamed. The HANDOVER's earlier "96 upstream-only / 90
  changed" figures counted the whole history between two release tags, not this
  merge-base diff; the real Genesis delta is the 54 files below.

The important correction this measurement forces: **Genesis does not contain the
themes, the Expressive shell, the updater scanners, the widget, Shizuku installers, or
`VyxelApp.kt`.** It is the multi-tenant + release-pipeline + store-source release. The
Track j leaf list assumed a large upstream-only surface; the surface that actually
exists is the one in the table below, and most of it is already merged.

## File-by-file matrix

Verdicts: **merged** — present in our tree and compiling (Track j port, commit
`00a5e67`); **ours** — our own file, keep; **port** — still to bring across;
**skip** — deliberately out, with the reason.

| Upstream file | Lines | Needs | Verdict |
|---|---:|---|---|
| `.github/dependabot.yml` | 14 | dependency bumps | skip — we control our own deps |
| `.github/scripts/build_listing.py` | 247 | AAB manifest → `listing.json` (7.a.i.zo) | ours — we have the same script already |
| `.github/scripts/prepare_tenant_build.py` | 181 | tenant flavor prep | ours — same, already present |
| `.github/workflows/build-tenant-apk.yml` | 182 | `f.vi` tenant build | ours — already present |
| `.github/workflows/release-aab.yml` | 599 | `7.a` release pipeline | ours — already present, diverged |
| `.gitignore` | 18 | ignores | merged |
| `.idea/.name` | 1 | IDE name | skip — noise |
| `HANDOVER.md` | 276 | upstream's own tracker | ours — our tracker supersedes it |
| `README.md` | 227 | branding, badges | ours — points at Zapier-codes/Storeapp (h.v) |
| `app/build.gradle.kts` | 191 | flavors, version from tag | ours — 7.a.viii.zi, diverged |
| `app/src/main/AndroidManifest.xml` | 57 | FCM service, tenant icon | merged |
| `app/src/main/java/.../AppColors.kt` | 283 | `ThemeName` enum, palettes | merged (two glass entries added, j.iv) |
| `app/src/main/java/.../AppComponents.kt` | 3,971 | shared widgets | ours — heavily diverged (Track d) |
| `app/src/main/java/.../AppData.kt` | 2,697 | state, sources, install | ours — diverged most (Track h/d) |
| `app/src/main/java/.../AppPreviews.kt` | 165 | previews | merged |
| `app/src/main/java/.../AppScreens.kt` | 1,884 | Classic screens | ours — diverged (Track d, h.v) |
| `app/src/main/java/.../AppStrings.kt` | 1,128 | strings | ours — 8 locales, diverged |
| `app/src/main/java/.../AppstoreApp.kt` | 63 | Application (was `VyxelApp.kt`) | merged — rename came with the port |
| `app/src/main/java/.../AppstoreMessagingService.kt` | 55 | FCM receiver (`e.i`) | merged |
| `app/src/main/java/.../HomeScreen.kt` | 896 | Classic home | ours — diverged (d.iii) |
| `app/src/main/java/.../InstallGateway.kt` | 90 | install entry (1.b.i.zo) | ours — same role, our version |
| `app/src/main/java/.../UpdateCheckWorker.kt` | 87 | worker | ours — Track h rewrote it |
| `app/src/main/java/.../api/DStoreCatalogClient.kt` | 277 | `7.b.iii.zo` | ours — already present |
| `app/src/main/java/.../api/DStoreEntry.kt` | 130 | `7.b.iii.zi` | ours — already present |
| `app/src/main/java/.../api/FederatedCatalogClient.kt` | 158 | `d.iv` federation | ours — already present |
| `app/src/main/java/.../api/MetadataManager.kt` | 29 | cdn base | merged |
| `app/src/main/java/.../api/PushRegistrar.kt` | 120 | `e.i` token registration | ours — already present |
| `app/src/main/java/.../api/TenantConfig.kt` | 254 | `1.c.ii.zi` | ours — already present |
| `app/src/main/java/.../api/Verifier.kt` | 181 | `1.b.i.zi` | ours — already present |
| `app/src/main/java/.../api/ZealotEntry.kt` | 183 | `1.a.ii.zo` | ours — already present |
| `app/src/main/java/.../api/ZealotTrust.kt` | 125 | `1.a.ii.zi` | ours — already present |
| `app/src/main/res/values-v31/themes.xml` | 8 | splash | merged |
| `app/src/main/res/values/strings.xml` | 2 | app name | merged (tenant-driven, d.i.zi) |
| `app/src/main/res/values/themes.xml` | 6 | base theme | merged |
| `app/src/tenant/res/...` (5 files) | 29 | tenant flavor resources | ours — already present |
| `app/src/test/.../DStoreCatalogClientTest.kt` | 173 | tests | ours — already present |
| `app/src/test/.../DStoreEntryTest.kt` | 122 | tests | ours — already present |
| `docs/RELEASING.md` | 96 | release runbook | ours — already present |
| `docs/index.html` | 444 | landing page | ours — points at Zapier-codes (h.v) |
| `gradlew` | 251 | wrapper | ours — diverged |
| `listing/README.md` | 18 | listing folder docs | ours — already present |
| `listing/category.txt` | 1 | store category | ours — already present |
| `listing/changelog.txt` | 0 | changelog | ours — already present |
| `listing/description.txt` | 17 | description | ours — already present |
| `listing/screenshots/.gitkeep` | 0 | screenshots dir | ours — already present |
| `listing/short_description.txt` | 1 | short description | ours — already present |
| `settings.gradle.kts` | 26 | modules | ours — diverged |
| `spec/tenant-config-schema.md` | 148 | schema contract | ours — already present |
| `spec/tenant-config.schema.json` | 84 | schema contract | ours — already present |
| `version.properties` | 5 | version source | ours — already present |

### Net of the matrix

* Every **code** file Genesis adds or changes is either already in our tree (`merged`,
  `ours`) or superseded by a later, larger piece of our own work (Tracks a, b, c, d, h).
  There is no unported upstream code file left in the Genesis delta.
* The remaining unported **content** is the branded theme palettes and the glass/blur
  pipeline, which are **not in the open-source tree at all** — see the theme inventory.
  That content is leaf j.xi (original work), not a port.

## Theme inventory

Upstream's Classic theme set, where each is defined, and every gate:

| Look | Defined | Palette | Gate |
|---|---|---|---|
| Light / Dark / Minimal / AMOLED / Sunset / Custom | `AppColors.kt` `ThemeName` + `themeColors()` | real, upstream | none |
| Cyberpunk | `OpenCoreTheme.kt` `CYBERPUNK_MODE` | **absent upstream** — falls back to Dark here | was `liquidGlassUnlocked` |
| Neon Punk | `OpenCoreTheme.kt` `NEON_PUNK_MODE` | **absent upstream** — falls back to Dark here | was `liquidGlassUnlocked` |
| Liquid Glass Dark | `AppColors.kt` `LIQUID_GLASS_DARK` | **absent upstream** — falls back to Dark here | was `liquidGlassUnlocked` |
| Liquid Glass Light | `AppColors.kt` `LIQUID_GLASS_LIGHT` | **absent upstream** — falls back to Light here | was `liquidGlassUnlocked` |
| Expressive `VyxelSkin` | `expressive/ui/theme/Skins.kt` | `Default` only | was `isPremium` per skin |

j.iv and j.v deleted the gate (`liquidGlassUnlocked`, `isPremium`, the PRO badge, the
padlock row, the unlock dialog, the load-time reset). Every look is selectable with no
key, payment or network check. The branded palettes and the real blur are the missing
half; they are original work (j.xi), bounded by the no-upstream-source rule.

## Strings, drawables, Gradle and manifest the port needs

* **Strings:** the ported screens read the existing `AppStrings` set plus two new keys
  for h.vi (`backgroundSelfUpdate`, `backgroundSelfUpdateDesc`) and the Expressive
  strings in `expressive/ui/ExpressiveStrings.kt`. No upstream-only string is needed.
* **Drawables:** none. Genesis ships no theme art in the open source, which is exactly
  why the Classic tiles now render a palette swatch (j.iv) instead of a screenshot.
* **Gradle:** already present and compiling — `firebase-messaging` (e.i),
  `androidx.work` (Track h), `androidx.datastore` (Expressive `SettingsStore`),
  `dev.rikka.shizuku` api+provider (j.x), `coil` and `coil3`, `security-crypto`.
* **Manifest:** already present — `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, `REQUEST_INSTALL_PACKAGES`, the Shizuku
  provider/activity, the FCM service, and the self-update receivers. Track h verified
  the two foreground-service permissions were added for `h.ii.zi`.

## Conflict list

The files that diverge most from upstream, so any future upstream merge is manual:

* `AppData.kt` — Tracks a/b/h (Zealot trust, install gateway, self-update) plus the
  multi-source list. Diverges most.
* `AppScreens.kt` / `AppComponents.kt` / `HomeScreen.kt` — Track d's Play-Store revamp
  and h.v's repo-pointer fix.
* `AppStrings.kt` — eight locales, tenant branding.
* `app/build.gradle.kts` / `settings.gradle.kts` / `gradlew` / `release-aab.yml` —
  our `7.a` release pipeline and flavors.

## Ordered leaf list, re-cut with the real sizes

1. **j.ii — About label + NOTICE.** Done (in `00a5e67`).
2. **j.iii — Foundation helpers.** Merged already; this leaf is the reconcile review.
   Present: `UiStyle.kt`, `VersionCompare.kt`, `SearchRanking.kt`, `GitHubQuotaMeter.kt`,
   `api/GitHubRateLimit.kt`. `VyxelApp.kt` does not exist upstream — it is
   `AppstoreApp.kt`, already merged. Nothing to port.
3. **j.iv — Classic themes, free.** Done in this delivery (gate and dialogs removed;
   free theme gallery added).
4. **j.v — Expressive theme layer.** Done in this delivery (`isPremium` and the
   padlock path deleted).
5. **j.vi — Expressive data layer fed by Zealot/D-Store.** Already wired:
   `expressive/data/CatalogRepository.kt`, `.../data/model/Models.kt`,
   `SettingsStore.kt`, `ClassicBridge.kt`, `.../core/net/Net.kt`, and
   `FederatedCatalogClient` as the first source. Reconcile-review only.
6. **j.vii — Expressive shell and screens.** Merged (`expressive/ui/*`); the `UiStyle`
   switch is exposed in Settings. **Corrected 2026-10-10: not review only.** The shell has
   no Zealot or D-Store source, and its install path did not run `Verifier`. Cut into
   j.vii.a (verification, done) to j.vii.d in `HANDOVER.md`.
7. **j.viii — Direct sources / Sources / Modules screens.** Blocked on decision (a).
8. **j.ix — Update scanners.** Present (`updater/UpdateScanEngine.kt`); reconcile only.
9. **j.x — Installers (Shizuku/root).** Present, off by default; blocked on decision (b)
   for turning on.
10. **j.xi — Our own skins and glass effect.** The genuinely unported half, original
    work, needs the operator's skin list. Until then the free set is j.iv's.
11. **j.xii — Today widget.** Present (`TodayWidgetProvider.kt`); reconcile only.
12. **j.xiii — Operator's device test.** Not code.
13. **h.vi — Optional background self-update check.** Done in this delivery (worker
    existed; the off-by-default setting is now exposed).
14. **h.vii — Operator's device test.** Not code.
