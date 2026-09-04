package com.ridelink.data.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The secondary "whole device library" convenience ARCHITECTURE §8.4 anticipates alongside SAF —
 * queries `MediaStore.Audio.Media` directly rather than walking a filesystem, so it reaches audio
 * files anywhere the OS itself has already indexed, not only inside one user-granted SAF tree.
 * Needs `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (API 31–32) — declared in the
 * manifest, requested at runtime by the UI layer, never assumed granted here.
 */
object MediaStoreLibraryScanner {
    private val PROJECTION =
        arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
        )

    suspend fun scan(context: Context): List<DiscoveredLocation> {
        val results = mutableListOf<DiscoveredLocation>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        context.contentResolver
            .query(collection, PROJECTION, null, null, null)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val name = cursor.getString(nameColumn)
                    val mimeType = cursor.getString(mimeColumn)
                    val isSupported =
                        name != null && (AudioFormats.isSupportedExtension(name) || AudioFormats.isSupportedMimeType(mimeType))
                    if (name != null && isSupported) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                        results += DiscoveredLocation(uri = uri.toString(), filename = name, sizeBytes = cursor.getLong(sizeColumn))
                    }
                }
            }
        return results
    }
}
