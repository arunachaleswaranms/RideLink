plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ridelink.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MigrationTestHelper reads exported schema JSON as a test asset, not from the source tree
    // directly, so the exported schemas/ directory must also be a test-asset source. The Phase 3
    // synthetic fixtures are wired the same way LibraryIndexerTest reads them as real files, not
    // regenerated or duplicated into the module — same cross-module-directory pattern
    // core/build.gradle.kts already uses for protocol/vectors/.
    sourceSets {
        named("androidTest") {
            assets.directories.add("$projectDir/schemas")
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

    // Room's schema-export discipline (docs/STATUS.md Phase 3 note): every version, including the
    // first, is committed here rather than only diffed in later. A real migration test can then
    // exist from schema version 1 onward instead of only from version 2.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.documentfile)
    ksp(libs.room.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.room.testing)
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
