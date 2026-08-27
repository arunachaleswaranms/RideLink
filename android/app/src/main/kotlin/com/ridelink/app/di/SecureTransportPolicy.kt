package com.ridelink.app.di

/**
 * The composition root's refusal to run the control plane in the clear (NFR-06, PROTOCOL §1).
 *
 * **This replaces the Phase 1a `BuildConfig.DEBUG` transport gate, and the guarantee is stronger
 * rather than weaker.** Phase 1a shipped a plaintext transport in the library's `main` source set
 * and used a debug-build check to avoid *constructing* it. Phase 1b deletes that path instead: the
 * only plaintext [com.ridelink.network.control.ControlChannel] in the repository lives in
 * `network/src/test`, so it is not compiled into the library at all and no app build — debug or
 * release — contains those bytes. There is nothing left for a build flag to gate.
 *
 * What remains is this: a composition root cannot assemble a session over a channel that says it
 * is not secure. Kept as a pure function so all of its behaviour is a fast JVM unit test rather
 * than something observable only by building and inspecting an APK, and kept at all — rather than
 * trusting the source-set split — because "no insecure channel exists" is a property of today's
 * tree, while this is a property of the code that runs.
 *
 * The mechanical guard that the source-set split *stays* true is
 * `network`'s `PlaintextTransportAbsenceTest`, which fails if a raw socket ever reappears in
 * production sources.
 */
fun requireSecureControlChannel(
    isSecure: Boolean,
    transportLabel: String,
) {
    check(isSecure) {
        "refusing to start a control session over an insecure transport ($transportLabel). " +
            "PROTOCOL §1 and NFR-06 require TLS 1.3; there is no plaintext production path."
    }
}
