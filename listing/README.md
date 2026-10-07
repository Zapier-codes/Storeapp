# Store listing

What the Android App Bundle does not hold. `.github/scripts/build_listing.py` (run by `release-aab.yml`)
reads this folder plus the bundle's own manifest into one `listing.json`. Edit these files, not the
script. The manifest facts (applicationId, version, label, SDK levels, permissions) are never written
here: they come from the bundle.

| File | What | Rule (copied from Zealot) |
|---|---|---|
| `short_description.txt` | One line under the name | 1 to 80 characters |
| `description.txt` | The full description | 1 to 4000 characters |
| `category.txt` | One Zealot category value, e.g. `tools` | must be one of Zealot's `App::CATEGORY_VALUES` |
| `changelog.txt` | What changed in the release being tagged | optional; empty gives a warning. **Rewrite it before each `v*` tag**: it is per release |
| `screenshots/` | Phone screenshots, shown in file-name order (`01-home.png`, `02-detail.png`, ...) | up to 8; PNG or JPEG, no transparency, not animated, 8 MB at most, each side 320 to 3840 px, long side at most twice the short side |
| `feature-graphic.png` | The banner | PNG or JPEG, no transparency, exactly 1024 x 500 |

Screenshots are committed (four, `01-home.png` to `04-settings.png`, already cropped to 2:1) and the build
**fails** with fewer than 2 (Zealot Task 43d-3). `icon.png` is the 512 x 512 store icon, composed from the
launcher's adaptive layers; both are sent to Zealot by `.github/workflows/listing-graphics.yml`, not by a release.
The feature graphic is **not committed yet**; the build goes on without it.
