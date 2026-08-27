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
