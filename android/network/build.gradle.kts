plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ridelink.network"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.webrtc.android)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)

    // TEST ONLY, never shipped — it is not in any `implementation` configuration, so it cannot
    // reach an APK. Android's own TLS stack *is* Conscrypt-over-BoringSSL, and the exporter the
    // six-digit SAS depends on is reached there through `android.net.ssl.SSLSockets`, a class that
    // does not exist on a plain JVM. A desktop JVM's default provider speaks TLS 1.3 but exposes
    // no exporter at all. Without this dependency, `TlsControlChannelTest` — the only test that
    // proves two RideLink peers derive the *same* six digits from one handshake — could not run
    // anywhere except on a phone. See `TlsProvider`'s doc comment and
    // docs/test-results/phase1b-security-spike-20260827.md for what the substitution does and does
    // not prove.
    testImplementation(libs.conscrypt.openjdk.uber)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // `SessionGate`'s trust-gate table is a shared vector (protocol/vectors/session-gate/), so
    // network's suite needs the same vectors directory core's does — a gate that disagreed between
    // the two platforms would otherwise only show up on two phones.
    systemProperty(
        "ridelink.protocolVectorsDir",
        rootProject.rootDir.parentFile
            .resolve("protocol/vectors")
            .absolutePath,
    )
    testLogging {
        events("passed", "skipped", "failed")
        // FULL, not the default SHORT: a CI failure whose assertion message is truncated to
        // `AssertionFailedError at Foo.kt:314` is not a debugging artefact, it is a prompt to guess.
        // Phase 2a hit exactly that — a real CI-only failure whose message the log did not carry.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
