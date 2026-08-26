# ADR-002 — LAN/hotspot transport with mDNS/DNS-SD discovery

**Status:** Accepted · 26 Aug 2026

## Context

The two phones must find and talk to each other with no internet and no server (FR-001, FR-002,
FR-024). Candidate peer-to-peer mechanisms are mostly single-platform:

- **Multipeer Connectivity** — Apple-only. Android cannot participate. Disqualifying.
- **Apple peer-to-peer Wi-Fi (AWDL)** — Apple-only. Disqualifying.
- **Wi-Fi Direct / Wi-Fi Aware** — Android-only; iOS exposes no interoperable API. Disqualifying.
- **Bluetooth as the phone-to-phone link** — explicitly excluded by FR-002, and bandwidth-hostile for file transfer.

What both platforms *do* implement identically is IP over Wi-Fi plus standard DNS-SD.

## Decision

Phone-to-phone transport is **IP over a shared Wi-Fi network** — a home/office LAN, or a hotspot
run by one of the phones. Discovery is **mDNS/DNS-SD**, service type `_ridelink._tcp`.

- Android: `NsdManager` (advertise and discover).
- iOS: `NWListener` (advertise) + `NWBrowser` (discover), with `NWConnection` for the socket.
- Both devices advertise *and* browse, so either person can initiate (Design Principle: "Phones are peers").
- TXT records carry only non-secret routing hints (ARCHITECTURE §4). No display name, no token, no library size.

Fallback for networks that block multicast: manual `host:port` + 6-digit code, presentable as a
QR code. Same protocol, different way of locating the peer. Deferred to Phase 1b.

## Consequences

- Works on both platforms with first-party APIs and no dependency.
- Requires a Wi-Fi network. On a ride that means a phone hotspot — which needs a Phase 1 measurement, since an iPhone hotspot may sleep its interface when idle and Android hotspot behaviour is vendor-dependent.
- iOS requires `NSLocalNetworkUsageDescription` and declared Bonjour service types; the user sees a local-network permission prompt.
- Some enterprise and guest networks block mDNS entirely — hence the manual fallback is a requirement, not a nicety.
- No NAT traversal, no STUN/TURN, no ICE servers anywhere in the system. This is what keeps the "no internet" claim literally true.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Multipeer Connectivity | Android cannot speak it |
| Wi-Fi Direct | iOS cannot speak it |
| BLE transport | FR-002 excludes it; far too slow for 10–50 MB tracks |
| Hardcoded IP, no discovery | Fragile against DHCP changes; poor UX. Kept only as the manual fallback |
| Cloud rendezvous server | Violates FR-024 and the entire local-first premise |

---

## Amendment A1 — 26 August 2026 — no stable identity in TXT records

**Status of the ADR: still Accepted.** The transport and discovery mechanism are unchanged. What
changes is the TXT record content.

The original decision said TXT records "carry only non-secret routing hints" and listed, among
them, `fp6` — the first 6 hex characters of the TLS certificate fingerprint — so that a *known*
peer could be recognised before a handshake.

That was a mistake, and it undercut the rest of the design. `fp6` is derived from a long-term
key, so it is **stable for the lifetime of the device identity**. Any passive observer on the
Wi-Fi — a café AP, another guest, anyone with a laptop — can read mDNS traffic and log it. A
stable 24-bit value is more than enough to recognise the same phone across days, networks and
locations. Rotating the peer handle (`pid`) alongside it bought nothing: the rotating value was
advertised in the same record as the stable one.

**The corrected TXT record set is the complete set:**

| Key | Value | Notes |
|---|---|---|
| `v` | protocol major version | |
| `dh` | 16 CSPRNG bytes as 32 hex | **ephemeral discovery handle.** Regenerated when advertising starts, at least every 15 min while advertising, and after every session. Not derived from `peer_id` or from the identity key. Never persisted |
| `plat` | `android` \| `ios` | optional, UI labelling only |

Explicitly **absent**: any long-term `peer_id`, any certificate or SPKI fingerprint or prefix
thereof, any pairing token, any SAS material, any library size or track count, and any user- or
device-chosen display name. The old `pid` key is renamed `dh` so that nothing in the record even
*looks* like a peer identity.

**Known-peer recognition moves after the TLS handshake**, where it belongs: the SPKI pin check of
ADR-012. The UX that `fp6` was buying is recovered without the privacy cost — when exactly one
trusted peer exists and auto-connect is enabled, discovering or tapping a peer attempts a silent
trusted connect, which either succeeds with no prompt or falls back to pairing. The user sees the
same two outcomes; the network sees nothing durable.

What remains observable and cannot be hidden: the service type `_ridelink._tcp` (so, that
RideLink is running), the IP address, and the port. mDNS requires a service type. This is
accepted and documented rather than pretended away.

Tests: `docs/TEST_PLAN.md` §4 asserts the advertised key set is a subset of `{v, dh, plat}`, that
no TXT value equals or prefixes `peer_id` or `identity_spki_sha256`, and that `dh` differs across
two advertising sessions.
