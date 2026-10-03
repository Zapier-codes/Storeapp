#!/usr/bin/env python3
"""Leaf 7.a.i.zo -- read the release AAB's own manifest and the committed `listing/` folder into one
`listing.json`, which leaf 7.a.ii.zo uploads to Zealot beside the bundle.

    build_listing.py <aab> <bundletool.jar> <listing-dir> <out.json>

Two sources, and each field has exactly one:
  * the AAB (through `bundletool dump manifest`): applicationId, version name and code, label, min and
    target SDK, permissions. Never typed by hand anywhere else, so it cannot drift from the bundle.
  * `listing/` (committed): description, short description, category, changelog, screenshots and the
    feature graphic. An AAB holds none of these.

It fails with a message on stderr and a non-zero exit when the manifest cannot be read or a listing file
breaks one of Zealot's rules. It never falls back to a guess: a half-read manifest uploaded as if it were
whole is worse than a red run. A missing screenshot or feature graphic is only a warning, because Zealot
does not gate a publish on them.

The limits below are COPIES of Zealot's (`app/services/listing_text.rb`, `listing_graphic_rules.rb`,
`App::CATEGORY_VALUES`); the sources are named at each so a drift is easy to find. Zealot checks them
again on upload, so a stale copy here can only be too strict or too lax, never unsafe.
"""
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# listing_text.rb
DESCRIPTION_MAX = 4000
SHORT_DESCRIPTION_MAX = 80

# App::CATEGORY_VALUES (APP_CATEGORIES + GAME_CATEGORIES in app/models/app.rb). Only the app group is
# listed: Storeapp is not a game. Add the game values if that ever changes.
CATEGORIES = {
    "art_and_design", "auto_and_vehicles", "beauty", "books_and_reference", "business", "comics",
    "communications", "dating", "education", "entertainment", "events", "finance", "food_and_drink",
    "health_and_fitness", "house_and_home", "libraries_and_demo", "lifestyle", "maps_and_navigation",
    "medical", "music_and_audio", "news_and_magazines", "parenting", "personalization", "photography",
    "productivity", "shopping", "social", "sports", "tools", "travel_and_local",
    "video_players_and_editors", "weather",
}

# listing_graphic_rules.rb
GRAPHIC_MAX_BYTES = 8 * 1024 * 1024
MIN_SIDE, MAX_SIDE, MAX_ASPECT = 320, 3840, 2
MAX_SCREENSHOTS = 8
FEATURE_SIZE = (1024, 500)
FORMATS = {"PNG": "image/png", "JPEG": "image/jpeg"}


def die(msg):
    print(f"build_listing: {msg}", file=sys.stderr)
    sys.exit(1)


def warn(msg):
    print(f"::warning::build_listing: {msg}")


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        die(f"`{' '.join(cmd[:4])} ...` failed ({p.returncode}): {p.stderr.strip()[:600]}")
    return p.stdout


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_manifest(aab, jar):
    xml_text = run(["java", "-jar", jar, "dump", "manifest", f"--bundle={aab}"])
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as e:
        die(f"bundletool printed a manifest that is not XML: {e}")
    if root.tag != "manifest":
        die(f"manifest root is <{root.tag}>, not <manifest>")
    return root


def need(value, what):
    if value is None or str(value).strip() == "":
        die(f"manifest has no {what}")
    return str(value).strip()


def to_int(value, what):
    v = need(value, what)
    if not re.fullmatch(r"\d+", v):
        die(f"manifest {what} is not a whole number: {v!r}")
    return int(v)


def resolve_label(label, aab, jar):
    """`android:label` is normally a resource reference (`@string/app_name` or a raw `@0x7f...` id),
    not text. A plain value is used as is; a reference is looked up in the bundle's own resources."""
    label = need(label, "application label")
    if not label.startswith("@"):
        return label
    ref = label[1:]
    ref = ref.removeprefix("ref/")  # tolerate `@ref/0x7f...`
    out = run(["java", "-jar", jar, "dump", "resources", f"--bundle={aab}", f"--resource={ref}", "--values"])
    m = re.search(r'\[STR\]\s+"(.*)"\s*$', out, re.MULTILINE)
    if not m or not m.group(1).strip():
        die(f"could not read a string for the label {label!r} from the bundle's resources; output began: {out[:300]!r}")
    return m.group(1)


def manifest_facts(aab, jar):
    root = read_manifest(aab, jar)
    app = root.find("application")
    if app is None:
        die("manifest has no <application>")
    sdk = root.find("uses-sdk")
    if sdk is None:
        die("manifest has no <uses-sdk>")
    perms = sorted({p.get(ANDROID_NS + "name") for p in root.findall("uses-permission") if p.get(ANDROID_NS + "name")})
    return {
        "application_id": need(root.get("package"), "package (applicationId)"),
        "version_code": to_int(root.get(ANDROID_NS + "versionCode"), "versionCode"),
        "version_name": need(root.get(ANDROID_NS + "versionName"), "versionName"),
        "label": resolve_label(app.get(ANDROID_NS + "label"), aab, jar),
        "min_sdk": to_int(sdk.get(ANDROID_NS + "minSdkVersion"), "minSdkVersion"),
        "target_sdk": to_int(sdk.get(ANDROID_NS + "targetSdkVersion"), "targetSdkVersion"),
        "permissions": perms,
    }


def read_text(path, required):
    if not path.is_file():
        if required:
            die(f"{path} is missing")
        return ""
    return path.read_text(encoding="utf-8").strip()


def graphic(path, kind):
    """Facts about one image from its bytes, never its name, then Zealot's rules for it."""
    size = path.stat().st_size
    try:
        with Image.open(path) as im:
            im.load()
            fmt, (w, h) = im.format, im.size
            has_alpha = "A" in im.getbands() or "transparency" in im.info
            animated = getattr(im, "is_animated", False)
    except Exception as e:  # unreadable or not an image
        die(f"{path}: not a readable image ({e})")
    problems = []
    if fmt not in FORMATS:
        problems.append(f"format {fmt} (only PNG or JPEG)")
    if has_alpha:
        problems.append("has transparency (Play wants no alpha)")
    if animated:
        problems.append("is animated")
    if size <= 0 or size > GRAPHIC_MAX_BYTES:
        problems.append(f"{size} bytes (limit {GRAPHIC_MAX_BYTES})")
    if kind == "feature_graphic":
        if (w, h) != FEATURE_SIZE:
            problems.append(f"{w}x{h} (must be exactly {FEATURE_SIZE[0]}x{FEATURE_SIZE[1]})")
    else:
        if not (MIN_SIDE <= w <= MAX_SIDE and MIN_SIDE <= h <= MAX_SIDE):
            problems.append(f"{w}x{h} (each side {MIN_SIDE} to {MAX_SIDE})")
        if max(w, h) > MAX_ASPECT * min(w, h):
            problems.append(f"{w}x{h} (long side over {MAX_ASPECT}x the short side)")
    if problems:
        die(f"{path}: " + "; ".join(problems))
    return {"file": str(path.as_posix()), "kind": kind, "device": "phone", "content_type": FORMATS[fmt],
            "byte_size": size, "width": w, "height": h, "sha256": sha256(path)}


def listing_folder(folder):
    short = read_text(folder / "short_description.txt", True)
    desc = read_text(folder / "description.txt", True)
    category = read_text(folder / "category.txt", True)
    changelog = read_text(folder / "changelog.txt", False)
    if not short or len(short) > SHORT_DESCRIPTION_MAX:
        die(f"short_description.txt is {len(short)} characters (1 to {SHORT_DESCRIPTION_MAX})")
    if not desc or len(desc) > DESCRIPTION_MAX:
        die(f"description.txt is {len(desc)} characters (1 to {DESCRIPTION_MAX})")
    if category not in CATEGORIES:
        die(f"category.txt says {category!r}, which is not one of Zealot's category values")
    if not changelog:
        warn("listing/changelog.txt is empty; this release will carry no changelog")

    shots_dir = folder / "screenshots"
    shots = sorted(p for p in shots_dir.glob("*") if p.is_file() and p.name != ".gitkeep") if shots_dir.is_dir() else []
    if len(shots) > MAX_SCREENSHOTS:
        die(f"{len(shots)} screenshots in {shots_dir} (at most {MAX_SCREENSHOTS})")
    if not shots:
        warn("no screenshots in listing/screenshots/; the listing goes out without any")
    screenshots = []
    for i, p in enumerate(shots):
        g = graphic(p, "screenshot")
        g["position"] = i
        screenshots.append(g)

    feature = [p for p in folder.glob("feature-graphic.*") if p.is_file()]
    if len(feature) > 1:
        die("more than one listing/feature-graphic.* file")
    if not feature:
        warn("no listing/feature-graphic.png; the listing goes out without one")
    return {
        "description": desc,
        "short_description": short,
        "category": category,
        "changelog": changelog,
        "screenshots": screenshots,
        "feature_graphic": graphic(feature[0], "feature_graphic") if feature else None,
    }


def main():
    if len(sys.argv) != 5:
        die("usage: build_listing.py <aab> <bundletool.jar> <listing-dir> <out.json>")
    aab, jar, folder, out = sys.argv[1], sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4])
    if not Path(aab).is_file():
        die(f"{aab} does not exist")
    if not folder.is_dir():
        die(f"{folder} is not a directory")
    manifest = manifest_facts(aab, jar)
    doc = {
        "schema": 1,
        "bundle": {"file": Path(aab).name, "sha256": sha256(aab), "byte_size": Path(aab).stat().st_size},
        # The listing's name is the manifest's label: one source, so the store name and the launcher
        # name cannot disagree.
        "name": manifest["label"],
        "manifest": manifest,
        "listing": listing_folder(folder),
    }
    out.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {out}: {manifest['application_id']} {manifest['version_name']} ({manifest['version_code']}), "
          f"{len(doc['listing']['screenshots'])} screenshot(s)")


if __name__ == "__main__":
    main()
