// NEW: the pure item factories shared by the message walk and the tool round-trip family, split out
// of ResponsesRequestBuilder.kt (2026-08-17, concentration campaign). appendToolResult
// (ResponsesInputTools.kt) needs toolResultImageMessage and imagePart, so without this shared type
// the tool family and the walk (ResponsesInputBuilder.kt) would point at each other — extracting these is
// what keeps the split ACYCLIC. Every relocated member kept its identical name and argument list.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.media.ImageFloor
import splice.core.wire.MediaSource

internal class ResponsesInputParts(minImageEdgePx: Int? = null) {

    private val floor = ImageFloor(minImageEdgePx)

    /** DR-155: the vendor floor this source is PROVEN to violate, or null for everything else.
     *  Asked BEFORE [imagePart] on every path, so imagePart's null keeps meaning exactly what it
     *  meant — the walk renders that null as "unsupported source" verbatim, and an undersized image
     *  is not an unsupported one. */
    internal fun belowFloor(source: MediaSource?): Int? = floor.violatedMinimum(source)

    /** The reason phrase for a below-floor drop, owned by [ImageFloor] so this dialect and the chat
     *  dialect cannot tell two different stories about the same event. */
    internal fun floorReason(minPx: Int): String = floor.reason(minPx)

    internal fun toolResultImageMessage(toolUseId: String, imageParts: List<JsonObject>): JsonObject =
        buildJsonObject {
            put("role", "user")
            put(
                FIELD_CONTENT,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", "[images from tool_result $toolUseId]")
                        },
                    )
                    imageParts.forEach { add(it) }
                },
            )
        }

    internal fun imagePart(source: MediaSource?): JsonObject? {
        // Bound to a local BEFORE the branch so `isNullOrEmpty`'s contract narrows the type the
        // compiler actually tracks. `source.data` is a property of a class from another module, so
        // a read of it is not smart-cast-stable and the guard above could not carry into the body —
        // that, and only that, is why this used to end in `!!`. Same guard, same value, now proven.
        val data = source?.data
        return when {
            source == null -> null
            source.type == "base64" && !data.isNullOrEmpty() -> {
                // Build the data URL once into a capacity-sized buffer — the base64 payload is often
                // multi-MB for screenshots; avoid intermediate template concat copies.
                val mime = source.mediaType?.takeIf { it.isNotEmpty() } ?: "image/png"
                val size = DATA_URL_PREFIX.length + mime.length + BASE64_SEPARATOR.length + data.length
                val url = StringBuilder(size)
                    .append(DATA_URL_PREFIX).append(mime).append(BASE64_SEPARATOR).append(data)
                    .toString()
                buildJsonObject {
                    put("type", "input_image")
                    put("image_url", url)
                }
            }
            source.type == "url" && !source.url.isNullOrEmpty() -> buildJsonObject {
                put("type", "input_image")
                put("image_url", source.url)
            }
            else -> null
        }
    }

    internal fun roleText(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put(FIELD_CONTENT, text)
    }
}

// Wire field names/pieces used by the input-item factories. All private-per-file: none is read
// outside this file's own functions. FIELD_CONTENT is deliberately NOT widened to internal —
// ResponsesHarvest.kt / ResponsesToolSearchController.kt already carry their own private copies of this literal
// (StringLiteralDuplication scopes per file), and a module-wide const of the same name collides
// with those pre-existing, out-of-scope declarations.
private const val FIELD_CONTENT = "content"
private const val DATA_URL_PREFIX = "data:"
private const val BASE64_SEPARATOR = ";base64,"
