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

`docs/PROTOCOL.md` is prose; prose can be read two ways. `vectors/` is executable. Both
`core:protocol` (JVM) and `RLProtocol` (Swift) run the **same** files through a table-driven
runner, so a wire incompatibility fails a unit test on a laptop instead of appearing as a
mystery on a motorcycle. The same technique covers `core:sync`/`RLSync` and
`core:queue`/`RLQueue`.

## Vector format

```json
{ "name": "unknown-payload-field-is-ignored",
  "input":    { "...": "..." },
  "expected": { "...": "..." } }
```

| Directory | Asserts |
|---|---|
| `vectors/envelope/` | round-trip, unknown-field/unknown-type tolerance, oversize + malformed rejection |
| `vectors/clock/` | 11 `(t1,t2,t3,t4)` samples with injected outliers ⇒ expected offset / rtt / jitter |
| `vectors/drift/` | drift series ⇒ expected ladder action (`none` / `nudge` / `seek` / `fail`) |
| `vectors/queue/` | concurrent mutations ⇒ expected final queue and revision |
| `vectors/manifest/` | two manifests ⇒ expected presence classification and delta |
| `vectors/ordering/` | out-of-order / duplicate / stale `command_seq` ⇒ expected applied set |
| `vectors/sas/` | fixed **test-only** exporter secret ⇒ expected 6-digit SAS |

## Rules

1. The spec is `docs/PROTOCOL.md`. This directory encodes it; it does not redefine it.
2. **Every protocol bug found on a device gets a vector added before the fix.** That is the regression discipline, made concrete.
3. A vector that passes on one platform only is a release blocker, not a warning.
4. `vectors/sas/` contains fabricated secrets for testing. **Never** put a real key, token or pairing code here.

> Populated in Phase 1 alongside the codecs that consume it — schemas without a consumer drift
> out of date. See `docs/STATUS.md` §7.
