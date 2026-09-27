# TenantConfig v1 (Storeapp leaf `1.c.i.zi`)

Shared cross-repo contract for the single-runtime multi-tenant model —
see this file's own `HANDOVER.md`, Track c, and the equivalent notes in
`D-Store/HANDOVER.md` (`6.b`) and `zealot/handover.md` (Task 37). This
repo owns the canonical copy; D-Store and zealot reference it by commit
rather than keeping a second copy that can drift.

This slice is **schema only** — this doc plus the machine-checkable
`tenant-config.schema.json` in this same directory. It does not build
the Gradle product-flavor packaging split (`1.c.i.zo`), the runtime
`TenantConfig` fetch/cache (`1.c.ii.zi`), or the Ed25519 verification
step (`1.c.ii.zo`, held until `1.a.ii.zi` lands) — those consume this
shape, they don't define it.

## Why this exists

The operator's recorded decision (`HANDOVER.md`, "Multi-tenant model:
single runtime deployable, not fork-per-tenant") is that one Storeapp
build serves every tenant, with tenant identity resolved at runtime
rather than by shipping a separate branded fork per white-label
operator. Android's own constraints mean `applicationId` and the
launcher name/icon are genuinely build-time-fixed — those alone stay
in Gradle product-flavor selection (`1.c.i.zo`). Everything else needs
a runtime shape both this app and its two sibling repos can agree on:
this document is that shape.

## Shape

```jsonc
{
  "schema_version": 1,
  "tenant_id": "acme-store",              // permanent once first published, DNS-label-safe — see "The tenant_id rule" below
  "generated_at": "2026-09-27T00:00:00Z",  // UTC ISO-8601, same convention as the catalog index
  "sequence": 4,                           // strictly increasing per publish for this tenant_id — anti-rollback, same mechanism as the catalog index's own `sequence`
  "expires_at": "2026-10-27T00:00:00Z",    // freshness bound; a reader refuses a record past this and falls back to its last-known-good cache
  "branding": {
    "display_name": "Acme App Store",      // in-app display copy only — NOT the launcher name, see "What's intentionally excluded"
    "primary_color_hex": "#FF6600",
    "logo_url": "https://acme.example.com/logo.png",
    "logo_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  },
  "cdn_base": "https://cdn.acme.example.com/appstore-metadata",
  "catalog_index_base_url": "https://acme.example.com/zealot-index",
  "domains": ["store.acme.example.com"],
  "is_default_tenant": false
}
```

## The `tenant_id` rule

Same posture as the catalog index's `slug`: generated once, permanent,
never reused, never regenerated on a rename. DNS-label-safe (lowercase
alphanumeric + hyphen, ≤63 chars, no leading/trailing hyphen) because
D-Store and Zealot resolve tenants by subdomain (`6.b.ii.zi`, Task
37b) — a `tenant_id` must always be valid as a subdomain segment even
on a deployment that resolves some other way, so one value works
everywhere rather than needing a second slug just for the web tier.

## Field notes

- **`cdn_base` vs `catalog_index_base_url` — deliberately two fields,
  not one.** `cdn_base` feeds `MetadataManager` (`1.c.ii.zi`) and points
  at unsigned, unverified upstream data by design — same posture as the
  now-superseded "No CDN migration" decision, whose actual intent (the
  six existing sources' data pipeline stays untouched) still holds:
  the default tenant's `cdn_base` MUST be exactly
  `https://nikhilkain.github.io/appstore-metadata`, the existing
  hardcoded string, so default install behavior is provably unchanged
  by this migration. `catalog_index_base_url` is the per-tenant
  equivalent of today's single `ZEALOT_CATALOG_INDEX_BASE_URL` env var
  and points at a signed, Ed25519-verified index. Conflating the two
  into one field would blur a real trust-boundary difference — one
  path has no verification anywhere in it today, the other is the one
  new capability (`1.a.ii.zi`) this whole program adds.
- **`domains` can be empty.** A tenant with no web-tier presence yet
  (an Android-only white-label spin-off, or one not yet live) is valid
  — empty means "not resolved by domain," not an error.
- **`is_default_tenant` is informational, not a trust signal.** Exactly
  one record should carry `true` — the seed tenant whose values
  reproduce today's pre-multi-tenant hardcoded behavior. Trust comes
  from the signature check (`1.c.ii.zo`), never from this flag.

## What's intentionally excluded

- **Packaging identity** (`applicationId`, launcher app name, launcher
  icon) is not in this schema. Android makes these build-time-fixed;
  they're Gradle product-flavor concerns (`1.c.i.zo`), not runtime
  config. `branding.display_name` above is in-app UI copy only and is
  never used for the launcher.
- **The verification key itself.** Same "the record is never its own
  trust anchor" rule the catalog index already follows — a
  `TenantConfig` document never carries the public key a reader should
  verify it against. `1.c.ii.zo` pins that key in Storeapp's own
  config, reusing the existing `PINNED_KEYS`/rotation-window mechanism
  in `zealot-trust.ts`-equivalent code, not a field on this payload.
- **Anything derivable from `sequence/expires_at` verification state**
  (last-good-cache timestamps, retry counts) — that's reader-side
  state (`1.c.ii.zi`), not part of the published record.

## Additive-only versioning policy

`schema_version` is fixed at `1` for this shape. Any future field is
additive with a default, never a required breaking change — a `v1`
reader encountering a `v2` record it doesn't understand should be able
to ignore unrecognized-but-additive fields for anything it doesn't
require, the same posture the catalog index commits to for its own
schema evolution. A genuinely breaking change (removing a required
field, changing a type) requires a new `schema_version` value and an
explicit reader-side refusal of unsupported versions — enforced by the
schema's `const: 1` today, exactly as the catalog index's
`SUPPORTED_SCHEMA_VERSION` check refuses an index at any other version
outright rather than best-effort parsing it.

## Open decisions (not resolved by this slice)

1. **Where the canonical spec copy lives long-term.** This slice puts
   it in Storeapp's own repo (`spec/`) since Storeapp is the first
   consumer building against it (`1.c.ii.zi`); D-Store and zealot
   reference it by commit hash rather than a live sync. Revisit if a
   fourth repo ever needs it, or if drift between the three referenced
   copies becomes a real problem — not speculative work now.
2. **Who publishes a `TenantConfig` record.** Zealot Task 37 (`37b`,
   `37c`) is where this gets a real publish path (tenant/org model,
   signed serving endpoint) — this schema only fixes the shape that
   work publishes against, it doesn't build the publisher.
3. **`logo_sha256` enforcement is a MUST in prose, not yet in the
   schema's structural constraints** — a schema-level
   `dependentRequired` (require `logo_sha256` whenever `logo_url` is
   present) was considered and deliberately left out of this slice to
   keep the first version simple; add it if `1.c.ii.zi`'s
   implementation finds bare `logo_url`-with-no-hash records in
   practice.

## Verifying this slice

No Android/Gradle build attempted (same sandbox limitation every other
leaf in this file already notes). What was run: `tenant-config.schema.json`
validated with `ajv`/`ajv-formats` (Node 22, the same tooling zealot's
own `catalog_index_v2.schema.json` was checked with) against six
fixtures — two valid (`valid-default-tenant.json`, seeded with the
exact existing hardcoded `cdn_base` string; `valid-full-tenant.json`,
every optional field populated) and four deliberately malformed
(missing required fields, `schema_version: 2`, an unrecognized extra
top-level field, a `tenant_id` violating the DNS-label pattern) — all
six resolved as expected. Neither `ajv` nor `ajv-formats` are
dependencies of this repo; used locally only, same as zealot's own
verification note for its schema.
