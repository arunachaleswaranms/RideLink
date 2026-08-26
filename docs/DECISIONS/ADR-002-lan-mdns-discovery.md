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
