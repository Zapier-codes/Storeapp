#!/usr/bin/env python3
"""Turn distr's build-config answer into the inputs of the `tenant` Gradle flavor (leaf f.vi).

Reads the JSON that `GET /api/public/v1/build-config/{id}` returned (spec/tenant-config-schema.md v1
plus `tenant_config_id`), checks every value it uses, and writes ONLY files and properties:

  app/tenant.properties                              tenant_id, version_code (read by app/build.gradle.kts)
  app/src/tenant/res/values/strings.xml              app_name (the launcher label), XML-escaped
  app/src/tenant/res/values/colors.xml               tenant_icon_background
  app/src/tenant/res/drawable-nodpi/tenant_icon_foreground.png   the launcher icon foreground

Nothing from the config is ever put on a shell command line or into a Gradle -P argument: the display
name only reaches the build as escaped XML text, the rest are strictly pattern-checked first. The config
comes from our own distr, but the display name and the logo are requester-supplied, so they are treated
as untrusted. Any problem exits non-zero with ONE line on stderr (the workflow reports it as the failure
message), and writes nothing outside the paths above.

Environment: DISTR_BASE_URL, TENANT_CONFIG_ID, RUN_NUMBER, GITHUB_OUTPUT (optional), the config path as argv[1].
"""
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from xml.sax.saxutils import escape

TENANT_ID_RE = re.compile(r"^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MAX_LOGO_BYTES = 256 * 1024  # same cap distr enforces when it accepts the icon
MAX_LABEL_CHARS = 50
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def fail(msg):
    print(msg.replace("\n", " ")[:300], file=sys.stderr)
    sys.exit(1)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def android_label(name):
    """XML-escape a launcher label and neutralise Android's own string-resource syntax."""
    name = "".join(ch for ch in name if ch.isprintable()).strip()
    name = re.sub(r"\s+", " ", name)[:MAX_LABEL_CHARS].strip()
    if not name:
        fail("branding.display_name is empty after cleaning")
    out = escape(name).replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    if out[0] in "@?":
        out = "\\" + out
    return name, out


def fetch_logo(url, expected_sha, distr_base):
    prefix = distr_base + "/api/public/v1/tenant-logos/"
    if not url.startswith(prefix) or not url.startswith("https://"):
        fail("logo_url is not on this distr instance's tenant-logos route")
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(urllib.request.Request(url, headers={"Accept": "image/png"}), timeout=30) as resp:
            if resp.status != 200:
                fail(f"logo download answered {resp.status}")
            data = resp.read(MAX_LOGO_BYTES + 1)
    except urllib.error.HTTPError as e:
        fail(f"logo download answered {e.code}")
    except Exception as e:  # noqa: BLE001 - one-line failure for the report
        fail(f"logo download failed: {type(e).__name__}")
    if len(data) > MAX_LOGO_BYTES:
        fail("logo is larger than 256 KB")
    if hashlib.sha256(data).hexdigest() != expected_sha:
        fail("logo SHA-256 does not match logo_sha256, refusing it")
    if not data.startswith(PNG_MAGIC):
        fail("logo is not a PNG")
    return data


def build_foreground(logo_bytes, color_hex, initial):
    """432x432 adaptive-icon foreground (108dp at xxxhdpi), logo kept inside the central 50%."""
    from io import BytesIO
    from PIL import Image, ImageDraw, ImageFont

    size, inner = 432, 216
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if logo_bytes is not None:
        logo = Image.open(BytesIO(logo_bytes))
        logo.load()
        logo = logo.convert("RGBA").resize((inner, inner), Image.LANCZOS)
        canvas.alpha_composite(logo, ((size - inner) // 2, (size - inner) // 2))
    elif initial:
        # No icon was supplied: a plain tile with the first letter, white on the brand color.
        draw = ImageDraw.Draw(canvas)
        try:
            font = ImageFont.truetype("DejaVuSans-Bold.ttf", 150)
        except OSError:
            font = ImageFont.load_default(size=150)
        draw.text((size / 2, size / 2), initial, fill=(255, 255, 255, 255), font=font, anchor="mm")
    return canvas


def main():
    if len(sys.argv) != 2:
        fail("usage: prepare_tenant_build.py <build-config.json>")
    distr_base = os.environ.get("DISTR_BASE_URL", "").strip().rstrip("/")
    expected_id = os.environ.get("TENANT_CONFIG_ID", "").strip().lower()
    run_number = os.environ.get("RUN_NUMBER", "").strip()
    if not distr_base.startswith("https://"):
        fail("DISTR_BASE_URL must be an https URL")
    if not UUID_RE.match(expected_id):
        fail("tenant_config_id input is not a UUID")
    if not run_number.isdigit() or int(run_number) < 1:
        fail("RUN_NUMBER is not a positive integer")

    try:
        with open(sys.argv[1], "rb") as f:
            cfg = json.loads(f.read(1 << 20))
    except Exception:  # noqa: BLE001
        fail("build-config answer is not valid JSON")

    if cfg.get("schema_version") != 1:
        fail(f"unsupported schema_version {cfg.get('schema_version')!r}")
    if str(cfg.get("tenant_config_id", "")).lower() != expected_id:
        fail("build-config answered for a different tenant_config_id")
    if cfg.get("is_default_tenant") is not False:
        fail("refusing to build a default-tenant record as a tenant APK")
    tenant_id = cfg.get("tenant_id", "")
    if not isinstance(tenant_id, str) or not TENANT_ID_RE.match(tenant_id):
        fail("tenant_id is not a DNS-label-safe id")
    branding = cfg.get("branding") or {}
    color = branding.get("primary_color_hex", "")
    if not isinstance(color, str) or not COLOR_RE.match(color):
        fail("branding.primary_color_hex is not #rrggbb")
    plain_name, label_xml = android_label(str(branding.get("display_name", "")))

    logo_url, logo_sha = branding.get("logo_url"), branding.get("logo_sha256")
    logo_bytes = None
    if logo_url or logo_sha:
        if not (isinstance(logo_url, str) and isinstance(logo_sha, str) and SHA256_RE.match(logo_sha)):
            fail("logo_url and a lowercase-hex logo_sha256 must come together")
        logo_bytes = fetch_logo(logo_url, logo_sha, distr_base)

    initial = plain_name[0].upper() if plain_name[0].isascii() and plain_name[0].isalnum() else ""
    try:
        foreground = build_foreground(logo_bytes, color, initial)
    except Exception as e:  # noqa: BLE001 - a bad image is a build failure with one line
        fail(f"could not render the launcher icon: {type(e).__name__}")

    res = os.path.join(ROOT, "app", "src", "tenant", "res")
    os.makedirs(os.path.join(res, "drawable-nodpi"), exist_ok=True)
    # The committed placeholder vector shares the resource name; two files of one name would be a
    # duplicate-resource error, so the placeholder goes before the PNG lands.
    placeholder = os.path.join(res, "drawable", "tenant_icon_foreground.xml")
    if os.path.exists(placeholder):
        os.remove(placeholder)
    foreground.save(os.path.join(res, "drawable-nodpi", "tenant_icon_foreground.png"), "PNG")

    with open(os.path.join(res, "values", "strings.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <string name="app_name">{label_xml}</string>\n</resources>\n')
    with open(os.path.join(res, "values", "colors.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <color name="tenant_icon_background">{color.upper()}</color>\n</resources>\n')
    with open(os.path.join(ROOT, "app", "tenant.properties"), "w", encoding="utf-8") as f:
        f.write(f"tenant_id={tenant_id}\nversion_code={int(run_number)}\n")

    app_id = "com.vythera.tenant.t_" + tenant_id.replace("-", "_")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"tenant_id={tenant_id}\napplication_id={app_id}\n")
    print(f"prepared tenant {tenant_id} ({app_id}), icon={'logo' if logo_bytes else 'initial'}")


if __name__ == "__main__":
    main()
