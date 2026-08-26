package com.ridelink.core.testutil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Loads shared golden vectors from `protocol/vectors/` at the repo root. The Kotlin tests must
 * execute these files directly rather than duplicating vector data as Kotlin literals
 * (CLAUDE.md "Shared protocol vectors — not optional").
 */
object Vectors {
    private val vectorsDir: File by lazy {
        val path =
            requireNotNull(System.getProperty("ridelink.protocolVectorsDir")) {
                "ridelink.protocolVectorsDir system property not set — see core/build.gradle.kts"
            }
        File(path)
    }

    fun load(relativePath: String): JsonElement {
        val file = File(vectorsDir, relativePath)
        require(file.exists()) { "vector file not found: ${file.absolutePath}" }
        return Json.parseToJsonElement(file.readText())
    }
}
