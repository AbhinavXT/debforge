package com.abhinavxt.debforge.ui.player

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.abhinavxt.debforge.player.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

private const val MIN_CONTRAST = 4.5
private const val MAX_MIX = 0.6f

private fun channel(c: Int): Double {
    val v = c / 255.0
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** WCAG relative luminance of an opaque ARGB colour. */
internal fun luminance(argb: Int): Double =
    0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

/** WCAG contrast ratio between two opaque ARGB colours, 1.0 to 21.0. */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun mix(from: Int, to: Int, t: Float): Int {
    fun ch(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * The top-of-gradient colour for an artwork colour: [art] blended into [surface] as far as it goes (up to 60%)
 * while [onSurface] keeps at least 4.5:1 contrast against it. Works for light and dark themes alike, because it
 * only ever moves from the surface toward the art. Returns [surface] itself when no blend is readable.
 */
internal fun artBackground(art: Int, surface: Int, onSurface: Int): Int {
    var t = MAX_MIX
    while (t > 0f) {
        val c = mix(surface, art, t)
        if (contrastRatio(onSurface, c) >= MIN_CONTRAST) return c
        t -= 0.05f
    }
    return surface
}

/** Picks dominant, then vibrant, then muted. Null when the image has no usable colour. */
private fun pickSwatch(bitmap: Bitmap): Int? {
    val p = Palette.from(bitmap).generate()
    return (p.dominantSwatch ?: p.vibrantSwatch ?: p.mutedSwatch)?.rgb
}

private val colorCache = LruCache<String, Int>(32)
private const val NONE = 0

/** The artwork's colour for [track], or null while loading, without art, or when extraction fails. */
@Composable
internal fun rememberArtColor(track: AudioTrack, enabled: Boolean): Color? {
    val context = LocalContext.current
    val artFile = track.artworkFile
    val artUrl = track.artworkUrl
    var result by remember(artFile, artUrl, enabled) { mutableStateOf<Color?>(null) }
    LaunchedEffect(artFile, artUrl, enabled) {
        if (!enabled) { result = null; return@LaunchedEffect }
        // Same source and cache key as TrackArt, so a bitmap the mini player decoded is reused.
        val (data, key) = withContext(Dispatchers.IO) {
            artFile?.takeIf { it.exists() }?.let { it to "art:${it.path}:${it.lastModified()}" }
        } ?: artUrl?.let { it to "art:$it" } ?: run { result = null; return@LaunchedEffect }
        val cached = colorCache.get(key)
        if (cached != null) { result = if (cached == NONE) null else Color(cached); return@LaunchedEffect }
        val argb = withContext(Dispatchers.Default) {
            runCatching {
                val mem = context.imageLoader.memoryCache?.get(MemoryCache.Key(key))?.bitmap
                    ?.takeIf { it.config != Bitmap.Config.HARDWARE }
                val bmp = mem ?: (context.imageLoader.execute(
                    ImageRequest.Builder(context).data(data).size(96, 96).allowHardware(false)
                        .memoryCachePolicy(CachePolicy.DISABLED).build()
                ) as? SuccessResult)?.drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                bmp?.let(::pickSwatch)
            }.getOrNull()
        }
        colorCache.put(key, argb ?: NONE)
        result = argb?.let { Color(it) }
    }
    return result
}
