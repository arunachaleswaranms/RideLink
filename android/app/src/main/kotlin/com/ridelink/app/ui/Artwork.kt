package com.ridelink.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ridelink.data.library.ArtworkCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Track artwork, decoded off the main thread at the size it is drawn (Phase 9A.5 §5).
 *
 * The cache file can be up to 1,024 px square ([com.ridelink.data.library.ArtworkProcessor]); the
 * library used to decode it at full size for a 48 dp thumbnail — about 4 MB of bitmap per row — and
 * did so for every row at once. Rows are lazy now, so only visible rows ask, and each asks for a
 * power-of-two downsample no smaller than its own pixel size. Decoded thumbnails are kept in a
 * small LRU keyed by reference and size, so scrolling back does not decode again.
 */
@Composable
internal fun Artwork(
    artworkRef: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(initialValue = artworkRef?.let { ThumbnailCache.get(it, sizePx) }, artworkRef, sizePx) {
        value =
            artworkRef?.let { ref ->
                ThumbnailCache.get(ref, sizePx)
                    ?: withContext(Dispatchers.IO) { ThumbnailCache.load(context, ref, sizePx) }
            }
    }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(bitmap = loaded, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            // A plain glyph rather than an icon dependency: one "no artwork" placeholder.
            Text(
                "♪",
                style = if (size >= 56.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private object ThumbnailCache {
    private const val MAX_ENTRIES = 160
    private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

    fun get(
        ref: String,
        sizePx: Int,
    ): ImageBitmap? = cache.get(key(ref, sizePx))

    fun load(
        context: Context,
        ref: String,
        sizePx: Int,
    ): ImageBitmap? {
        val path = ArtworkCache(context).fileFor(ref).absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        val decoded =
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
            } else {
                null
            }
        return decoded?.asImageBitmap()?.also { cache.put(key(ref, sizePx), it) }
    }

    private fun key(
        ref: String,
        sizePx: Int,
    ) = "$sizePx/$ref"
}
