package ro.davinci.checker.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.LinkedHashMap

/**
 * Cache mic de icoane pentru listă: 550+ aplicații × bitmap ar fi prea mult,
 * dar un LRU de câteva sute acoperă ecranul și derularea normală.
 */
object IconCache {

    private const val MAX_ENTRIES = 256
    private const val SIZE_PX = 128

    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    fun load(context: Context, packageName: String): ImageBitmap? {
        synchronized(cache) { cache[packageName]?.let { return it } }

        val drawable = runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull() ?: return null

        val bitmap = runCatching {
            drawable.toBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull() ?: return null

        synchronized(cache) { cache[packageName] = bitmap }
        return bitmap
    }
}