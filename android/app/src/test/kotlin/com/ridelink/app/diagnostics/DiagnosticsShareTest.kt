package com.ridelink.app.diagnostics

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagnosticsShareTest {
    @TempDir
    lateinit var cacheDir: File

    @Test
    fun `two exports have different paths and preserve their own bytes`() {
        val first = DiagnosticsShare.write(cacheDir, "first", FIRST_ID)
        val second = DiagnosticsShare.write(cacheDir, "second", SECOND_ID)

        assertNotEquals(first.absolutePath, second.absolutePath)
        assertNotEquals(first.name, second.name)
        assertEquals(File(cacheDir, "diagnostics").canonicalFile, first.canonicalFile.parentFile)
        assertEquals(File(cacheDir, "diagnostics").canonicalFile, second.canonicalFile.parentFile)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }

    @Test
    fun `the fifth export prunes to four unique snapshots without reusing a path`() {
        val files = (1..5).map { index -> DiagnosticsShare.write(cacheDir, "$index", id(index)) }

        assertFalse(files.first().exists())
        assertTrue(files.last().exists())
        assertEquals("5", files.last().readText())
        assertEquals(4, File(cacheDir, "diagnostics").listFiles()!!.size)
        assertEquals(5, files.map { it.absolutePath }.toSet().size)
    }

    @Test
    fun `crafted IDs cannot escape the diagnostics directory`() {
        listOf("../escape", "a/b", "..", "a\\b", "serial-123").forEach { id ->
            assertFailsWith<IllegalArgumentException> { DiagnosticsShare.write(cacheDir, "secret", id) }
        }
        assertFalse(File(cacheDir, "diagnostics").exists())
    }

    @Test
    fun `an existing ID cannot be overwritten`() {
        val first = DiagnosticsShare.write(cacheDir, "first", FIRST_ID)

        assertFailsWith<IOException> { DiagnosticsShare.write(cacheDir, "second", FIRST_ID) }
        assertEquals("first", first.readText())
    }

    private fun id(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private companion object {
        const val FIRST_ID = "00000000-0000-4000-8000-000000000001"
        const val SECOND_ID = "00000000-0000-4000-8000-000000000002"
    }
}
