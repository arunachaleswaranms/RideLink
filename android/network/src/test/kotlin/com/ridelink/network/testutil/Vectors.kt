package com.ridelink.network.testutil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Loads shared golden vectors from `protocol/vectors/` at the repo root.
 *
 * A deliberate twin of `core`'s `com.ridelink.core.testutil.Vectors`: both are five lines of test
 * plumbing, and `core` is a `kotlin("jvm")` module whose test source set an Android library module
 * cannot depend on. Sharing it would mean publishing test fixtures between modules for the sake of
 * a file reader (CLAUDE.md "Shared protocol vectors — not optional" is about the *vector files*
 * being shared, which they are — this is the loader, not the data).
 */
object Vectors {
    private val vectorsDir: File by lazy {
        val path =
            requireNotNull(System.getProperty("ridelink.protocolVectorsDir")) {
                "ridelink.protocolVectorsDir system property not set — see network/build.gradle.kts"
            }
        File(path)
    }

    fun load(relativePath: String): JsonElement {
        val file = File(vectorsDir, relativePath)
        require(file.exists()) { "vector file not found: ${file.absolutePath}" }
        return Json.parseToJsonElement(file.readText())
    }
}
