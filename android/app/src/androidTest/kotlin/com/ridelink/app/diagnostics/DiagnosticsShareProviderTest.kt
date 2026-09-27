package com.ridelink.app.diagnostics

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class DiagnosticsShareProviderTest {
    @Test
    fun twoExportsHaveDistinctProviderUrisAndReadOnlyNonPrefixGrants() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = DiagnosticsShare.write(context.cacheDir, "first")
        val second = DiagnosticsShare.write(context.cacheDir, "second")
        val authority = context.packageName + ".diagnostics"
        val firstUri = FileProvider.getUriForFile(context, authority, first)
        val secondUri = FileProvider.getUriForFile(context, authority, second)

        assertNotEquals(firstUri, secondUri)
        assertEquals(authority, firstUri.authority)
        assertEquals(authority, secondUri.authority)
        assertTrue(firstUri.path!!.startsWith("/diagnostics/"))
        assertTrue(secondUri.path!!.startsWith("/diagnostics/"))
        val firstText = context.contentResolver.openInputStream(firstUri)!!.use { it.reader().readText() }
        val secondText = context.contentResolver.openInputStream(secondUri)!!.use { it.reader().readText() }
        assertEquals("first", firstText)
        assertEquals("second", secondText)

        val provider = context.packageManager.resolveContentProvider(authority, 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)

        val chooser = DiagnosticsShare.chooser(context, second)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(secondUri, send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertEquals(secondUri, send.clipData!!.getItemAt(0).uri)
        assertEquals(1, send.clipData!!.itemCount)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        assertEquals(0, chooser.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, chooser.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }
}
