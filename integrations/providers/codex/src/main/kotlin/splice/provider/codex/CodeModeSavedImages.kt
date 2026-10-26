// NEW: a script output saved before Oct 26, 2026 can carry images admission cut at 64 KiB; its replay puts them back.
package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

/**
 * Before Oct 26, 2026 admission cut a tool value whole like text, its data URLs included, so a script that passed an
 * image to `image()` saved a data URL cut at 64 KiB with ` [truncated N chars]` on its end. No provider reads that
 * image, and the record replays the script's output on every later turn, so every later request in the conversation
 * failed. The result's media follow-up was saved whole, so the replay puts each such image back from it: the one data
 * URL that starts with the prefix admission kept and is longer than it by no more than the N chars it cut.
 */
internal object CodeModeSavedImages {
    /** [output], a saved native script output, with each cut image whole again where [record]'s media holds it. */
    fun whole(output: JsonElement, record: CodeModeRecord): JsonElement {
        val items = output as? JsonArray ?: return output
        if (items.none { cut(it) != null }) return output
        val urls = record.accepted.durableMedia().flatMap(::imageUrls)
        return JsonArray(items.map { item -> restored(item, urls) })
    }

    private fun restored(item: JsonElement, urls: List<String>): JsonElement {
        val cut = cut(item) ?: return item
        val url = urls.firstOrNull { it.startsWith(cut.kept) && it.length - cut.kept.length in 1..cut.gone }
            ?: return item
        return JsonObject(cut.image + (FIELD_IMAGE_URL to JsonPrimitive(url)))
    }

    /** [item] when it is an image whose data URL admission cut, with the prefix it kept and the count of chars it
     *  cut from the whole tool value. */
    private fun cut(item: JsonElement): CutImage? {
        val image = (item as? JsonObject)?.takeIf { JsonScalars.str(it, FIELD_TYPE) == TYPE_INPUT_IMAGE } ?: return null
        val url = JsonScalars.str(image, FIELD_IMAGE_URL)
            ?.takeIf { it.startsWith(DATA_PREFIX) && it.endsWith(MARKER_END) }
        val start = url?.lastIndexOf(MARKER_START) ?: -1
        val gone = url?.takeIf { start >= 0 }?.substring(start + MARKER_START.length)?.removeSuffix(MARKER_END)
            ?.toIntOrNull()
        val kept = url?.takeIf { start >= 0 }?.substring(0, start)?.takeIf { BASE64_MARK in it }
        return if (kept == null || gone == null) null else CutImage(image, kept, gone)
    }

    private fun imageUrls(element: JsonElement): List<String> = when (element) {
        is JsonObject -> {
            val own = JsonScalars.str(element, FIELD_IMAGE_URL)
                ?.takeIf { JsonScalars.str(element, FIELD_TYPE) == TYPE_INPUT_IMAGE }
            listOfNotNull(own) + element.values.flatMap(::imageUrls)
        }
        is JsonArray -> element.flatMap(::imageUrls)
        is JsonPrimitive -> emptyList()
    }

    private data class CutImage(val image: JsonObject, val kept: String, val gone: Int)
}

private const val FIELD_IMAGE_URL = "image_url"
private const val FIELD_TYPE = "type"
private const val TYPE_INPUT_IMAGE = "input_image"
private const val DATA_PREFIX = "data:"
private const val BASE64_MARK = ";base64,"

// why: the marker CodeModeLimits.boundedText writes after what it cut: " [truncated N chars]".
private const val MARKER_START = " [truncated "
private const val MARKER_END = " chars]"
