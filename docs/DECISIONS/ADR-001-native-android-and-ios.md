# ADR-001 — Native Android and iOS, no cross-platform UI framework

**Status:** Accepted · 26 Aug 2026

## Context

RideLink runs on exactly two devices: an Android rider phone and an iPhone pillion phone. A
cross-platform framework (Flutter, React Native, KMP with Compose Multiplatform) would reduce
duplicated UI code.

But the product's difficulty is not UI. It is audio routing, Bluetooth profile behaviour,
background execution and real-time scheduling — the four areas where cross-platform frameworks
are thinnest and where every bug would have to be debugged *through* the abstraction. The
requirements document reaches the same conclusion independently (REQUIREMENTS §19: "Native
Kotlin + Swift apps with a documented shared protocol").

## Decision

Two native apps: Kotlin + Jetpack Compose, Swift + SwiftUI. No shared UI framework, no shared
binary runtime.

Sharing happens through **specification rather than code**: the wire protocol
(`docs/PROTOCOL.md`), JSON Schemas, documented state machines, and — most importantly — shared
golden test vectors in `protocol/vectors/` that both platforms' unit suites execute.

## Consequences

- Domain logic (FSM, clock maths, drift ladder, queue algebra, manifest diff) is written twice. Mitigated by keeping it pure, small, and pinned to identical vectors, so divergence fails a test rather than surfacing on a motorcycle.
- Audio code is written natively for each platform, which is where we want the control.
- Full access to `AudioManager`, `AVAudioSession`, `MediaSessionService`, `AVAudioEngine` and WebRTC without wrapper lag.
- Two build systems, two toolchains, two CI paths.
- Kotlin Multiplatform remains available later for `core:*` only — the module boundaries in ARCHITECTURE §9.1 are drawn so that this stays a contained change rather than a rewrite.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Flutter / React Native | Audio routing and background-audio behaviour would sit behind a plugin layer; WebRTC plugins lag upstream; debugging the highest-risk subsystem becomes indirect |
| KMP + Compose Multiplatform | Compose on iOS is still maturing; audio still needs native code, so the saving is only the easy part |
| KMP for `core:*` only, native UI | Genuinely attractive and not foreclosed. Deferred: it adds toolchain complexity now, in exchange for de-duplicating code that does not yet exist. Revisit after Phase 5 |
