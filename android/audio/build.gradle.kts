plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ridelink.audio"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Same cross-module fixture wiring data/build.gradle.kts uses for LibraryIndexerTest.
    sourceSets {
        named("androidTest") {
            assets.directories.add(
                rootProject.rootDir.parentFile
                    .resolve("test-media/synthetic")
                    .path,
            )
        }
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
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
