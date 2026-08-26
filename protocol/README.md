# protocol/

The shared seam between the two native apps ([ADR-001](../docs/DECISIONS/ADR-001-native-android-and-ios.md)).
Since Android and iOS implement the same logic twice, this directory is what keeps the two
implementations honest.

```
protocol/
├── README.md
├── schema/     JSON Schema per message type — the normative wire shape
└── vectors/    Golden test cases, executed by BOTH platforms' unit suites
```

## Why vectors, not just a spec

`docs/PROTOCOL.md` is prose; prose can be read two ways. `vectors/` is executable. Both the
Android `core` module (JVM) and `RideLinkCore` (Swift) run the **same** files through a
table-driven runner, so a wire incompatibility fails a unit test on a laptop instead of appearing
as a mystery on a motorcycle.

## Vector format

```json
{ "name": "unknown-payload-field-is-ignored",
  "input":    { "...": "..." },
  "expected": { "...": "..." } }
```

| Directory | Asserts |
|---|---|
| `vectors/envelope/` | round-trip, unknown-field/unknown-type tolerance, oversize rejection (262 144 + 1), malformed rejection |
| `vectors/clock/` | 11 `(t1,t2,t3,t4)` samples with injected outliers ⇒ expected offset / rtt / jitter |
| `vectors/drift/` | drift series ⇒ expected ladder action (`none` / `nudge` / `seek` / `fail`), including suspension while a route is transitioning |
| `vectors/queue/` | concurrent mutations ⇒ expected final queue and revision |
| `vectors/manifest/` | two manifests ⇒ expected presence classification and delta |
| `vectors/manifest-paging/` | page splitting for **1 / 1 000 / 5 000** entries and for pathological metadata ⇒ expected page boundaries, per-page byte bound, and `MANIFEST_END` digest |
| `vectors/manifest-paging-errors/` | 12 cases: missing / duplicated / reordered page, wrong `manifest_id`, changed revision, wrong `base_revision`, count mismatch, digest mismatch, truncated stream, page timeout, malformed page, `removed[]` on a full manifest ⇒ expected error **and** unchanged live manifest |
| `vectors/ordering/` | out-of-order / duplicate / stale `command_seq` ⇒ expected applied set |
| `vectors/sas/` | fixed 32-byte **test-only** exporter output ⇒ expected 6-digit SAS |
| `vectors/identity/` | `identity_spki_sha256` formatting, pin match/mismatch, certificate re-issue with unchanged SPKI ⇒ still trusted |
| `vectors/dedup/` | `conn_tiebreak` pairs ⇒ which side's initiated connection survives; equal values ⇒ both close |
| `vectors/audio-state/` | `AUDIO_STATE` round-trip, `revision` monotonicity, derived `media_quality`, unknown enum tolerated as `unknown` |

The six directories from `manifest-paging/` onward exist because of the pre-Phase-1 correction
pass — each one covers a place where the two implementations could disagree *silently*. See
[`docs/STATUS.md`](../docs/STATUS.md#2-what-changed-in-the-correction-pass).

## The three vector sets that matter most

**`sas/`** — a big-endian/little-endian mix-up, a missing zero-pad or an extra HKDF layer means
the two phones show two different six-digit codes. To the users that is indistinguishable from a
man-in-the-middle attack, and the *correct* response to seeing it would be to refuse to pair. So
this must fail on a laptop, never on a bike. The algorithm is pinned byte-for-byte in
[PROTOCOL §4.5.1](../docs/PROTOCOL.md#451-the-six-digit-sas--exact-construction) and the ten
expected values are tabulated in
[§4.5.2](../docs/PROTOCOL.md#452-sas-golden-vectors) — including `000000`, leading-zero cases and
two different inputs that both reduce to `999999`.

**`manifest-paging/`** — page boundaries are computed by the *sender* and validated by the
*receiver*, so if the two platforms measure a page differently, one peer's pages are rejected by
the other and the catalogue never syncs. The vectors fix the page boundaries for a given entry
list and budget, so both implementations must split identically.

**`dedup/`** — the vectors deliberately include cases where the initiator is *not* the leader, so
an implementation that conflates "I called `connect()`" with "I am the leader" fails immediately
rather than working by coincidence in the lab.

## Rules

1. The spec is `docs/PROTOCOL.md`. This directory encodes it; it does not redefine it.
2. **Every protocol bug found on a device gets a vector added before the fix.** That is the regression discipline, made concrete.
3. A vector that passes on one platform only is a release blocker, not a warning.
4. `vectors/sas/` contains **fabricated** exporter secrets for testing. **Never** put a real key, token, exporter output or pairing code here.
5. Vectors are inputs and expectations only — no platform types, no timestamps read from a clock, nothing that changes between runs.

> Populated in Phase 1 alongside the codecs that consume it — schemas without a consumer drift
> out of date. See `docs/STATUS.md` §7.
