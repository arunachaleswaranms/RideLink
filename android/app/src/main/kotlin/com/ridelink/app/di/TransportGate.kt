package com.ridelink.app.di

/**
 * Pure enforcement primitive behind [AppContainer]'s release-transport guard (this session's
 * brief §4): when [allowed] is `false`, [factory] is never called — so a type capable of putting
 * bytes on a real socket (`NsdDiscoveryController`, `ControlSessionManager`) is never
 * *instantiated*, not merely unused. Extracted as a plain function (no `android.*`, no
 * `Context`) so the "never constructed" guarantee is a fast JVM unit test rather than something
 * only observable on-device.
 */
fun <T> gatedByPlaintextTransport(
    allowed: Boolean,
    factory: () -> T,
): T? = if (allowed) factory() else null

/**
 * The non-bypassable Release-transport invariant (CI-stabilization session's brief §11):
 * `isDebugBuild` is ANDed into the result regardless of what `requested` is, so a Release build
 * can never enable the plaintext transport even if a future caller mistakenly constructs
 * [AppContainer] with `requestedPlaintextTransportAllowed = true`. Extracted as a pure function
 * (no `BuildConfig`, no `Context`) so all four combinations are a fast JVM unit test rather than
 * something only observable by actually building a Release APK:
 *
 * | `isDebugBuild` | `requested` | result |
 * |---|---|---|
 * | `true` | `true` | `true` (allowed) |
 * | `true` | `false` | `false` (blocked) |
 * | `false` | `true` | `false` (**blocked** — the case a careless call site could otherwise slip through) |
 * | `false` | `false` | `false` (blocked) |
 */
fun effectivePlaintextTransportAllowed(
    isDebugBuild: Boolean,
    requested: Boolean,
): Boolean = isDebugBuild && requested
