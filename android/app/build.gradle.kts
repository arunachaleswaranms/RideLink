plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ridelink.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ridelink.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":network"))
    implementation(project(":audio"))
    implementation(project(":data"))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    // AppContainer builds the one Room instance directly (RideLinkDatabase's class lives in
    // :data, but Room.databaseBuilder itself is not re-exported by an `implementation` dependency).
    implementation(libs.room.runtime)
    // ADR-022: RideForegroundService builds the one real `MediaSession` here, in `app` — the one
    // module allowed to depend on both `audio` (where the real ExoPlayer lives) and the session it
    // is attached to. `androidx-media` is the older support-media artifact `MediaStyle`/
    // `MediaSessionCompat.Token` come from; media3-session only pulls it in at runtime, not compile
    // time (see the version catalog comment).
    implementation(libs.media3.session)
    implementation(libs.androidx.media)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)

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
