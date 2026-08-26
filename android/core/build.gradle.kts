plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    // core is pure JVM/Kotlin with no android.* on its compile classpath (ARCHITECTURE §9.1) —
    // this is also where every shared protocol vector runs.
    systemProperty(
        "ridelink.protocolVectorsDir",
        rootProject.rootDir.parentFile
            .resolve("protocol/vectors")
            .absolutePath,
    )
    testLogging {
        events("passed", "skipped", "failed")
    }
}
