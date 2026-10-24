package splice.upstream.codemode

import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.ceil

private const val NATIVE_AUDIO_TOKENS_PER_SECOND: Double = 10.0
private const val NATIVE_AUDIO_CACHE_SIZE: Int = 32

/** Codex audio estimate: duration at ten tokens per second, or the UTF-8 URL estimate when unreadable. */
public object NativeCodeModeAudio {
    private val cache = object : LinkedHashMap<String, Long>(NATIVE_AUDIO_CACHE_SIZE, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > NATIVE_AUDIO_CACHE_SIZE
    }

    public fun tokens(audioUrl: String): Long {
        val key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(audioUrl.encodeToByteArray()))
        synchronized(cache) { cache[key]?.let { return it } }
        val duration = duration(audioUrl)
        val count = duration?.let { ceil(it * NATIVE_AUDIO_TOKENS_PER_SECOND).toLong() }
            ?: NativeCodeModeTruncation.tokens(audioUrl)
        synchronized(cache) { cache[key] = count }
        return count
    }

    private fun duration(url: String): Double? {
        val decoded = decode(url) ?: return null
        val data = NativeAudioBytes(decoded.second)
        return when (decoded.first) {
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> NativeWaveDuration.read(data)
            "audio/mpeg", "audio/mp3" -> NativeMpegDuration.read(data)
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> NativeMp4Duration.read(data)
            "audio/ogg" -> NativeOggDuration.read(data)
            "audio/webm" -> NativeWebmDuration.read(data)
            else -> null
        }?.takeIf { it.isFinite() && it >= 0 }
    }

    private fun decode(url: String): Pair<String, ByteArray>? {
        val comma = url.indexOf(',')
        if (comma < 0 || !url.startsWith("data:", ignoreCase = true)) return null
        val metadata = url.substring("data:".length, comma).split(';')
        if (metadata.drop(1).none { it.equals("base64", ignoreCase = true) }) return null
        return try {
            metadata.first().lowercase(Locale.ROOT) to Base64.getDecoder().decode(url.substring(comma + 1))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
