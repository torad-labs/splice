// NEW: V4-179 — the ONE renderer for the images a tool_result carries, shared by the ordinary walk
// (ResponsesInputTools.appendToolResult) and the code-mode bridge (CodexCodeModeTurnBuilder).
//
// function_call_output.output is string-only, so a screenshot inside a tool_result rides as a
// follow-up user message of input_image parts, and every image that cannot ride is ANNOUNCED — the
// v25 marker doctrine, with DR-164's "unsupported source" and DR-155's floor reason. Since 2026-08-17
// that logic lived inline in appendToolResult. Code mode needs the same items for a tool_result the
// bridge owns: it persists them beside the script's bounded output and replays them from the record
// (CodexCodeModeHistory), and the bridge recognises its own follow-ups in the client's history by
// byte-equality with what THIS renderer produced. Two renderers would be two readings of one
// tool_result that drift apart silently, so there is one, and it is pure: a ToolResultBlock in, the
// ordered items out, nothing else consulted.
//
// PUBLIC because provider-codex is its second consumer; the rest of the tool round-trip family stays
// internal — this is the narrow surface, not the builder.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.wire.ImageBlock
import splice.core.wire.ToolResultBlock

public class ResponsesToolResultMedia(private val quirks: ResponsesQuirks) {

    private val parts = ResponsesInputParts(quirks.minImageEdgePx)

    /** The follow-up items for [block]'s images, in the order the wire has carried them since v25:
     *  the image message (readable, above-floor images), then the unsupported-source marker, then
     *  the floor marker — each only when it has something to say. [ToolResultFollowUps.dispositions]
     *  answers, per image in content order, what happened to it, so a caller can tell the model. */
    public fun followUps(block: ToolResultBlock): ToolResultFollowUps {
        val images = block.content.filterIsInstance<ImageBlock>()
        val dispositions = images.map { image ->
            val floor = parts.belowFloor(image.source)
            when {
                floor != null -> ImageDisposition(delivered = false, reason = parts.floorReason(floor))
                parts.imagePart(image.source) == null ->
                    ImageDisposition(delivered = false, reason = UNSUPPORTED_SOURCE)
                else -> ImageDisposition(delivered = true, reason = null)
            }
        }
        val imageParts = images.zip(dispositions)
            .filter { (_, disposition) -> disposition.delivered }
            .mapNotNull { (image, _) -> parts.imagePart(image.source) }
        val unreadable = dispositions.count { !it.delivered && it.reason == UNSUPPORTED_SOURCE }
        val undersized = images.zip(dispositions).filter { (_, d) -> !d.delivered && d.reason != UNSUPPORTED_SOURCE }
        val items = buildList {
            if (imageParts.isNotEmpty()) add(parts.toolResultImageMessage(block.toolUseId, imageParts))
            // Unreadable first, then the floor — the same order the chat sibling's markerFold emits
            // them in, so a tool_result carrying one of each tells the same story in both dialects.
            if (unreadable > 0) add(omission(block.toolUseId, unreadable, UNSUPPORTED_SOURCE))
            // DR-155: an undersized image read perfectly and the backend simply refuses images that
            // small, so it is never relabelled "unsupported source" — its own count, its own reason.
            undersized.firstOrNull()?.second?.reason?.let { add(omission(block.toolUseId, undersized.size, it)) }
        }
        return ToolResultFollowUps(items, dispositions)
    }

    private fun omission(toolUseId: String, count: Int, why: String): JsonObject = parts.roleText(
        "user",
        "[$count image(s) from tool_result $toolUseId omitted by ${quirks.providerTag} proxy: $why]",
    )
}

/** What [ResponsesToolResultMedia.followUps] rendered: the wire items, and one disposition per image. */
public data class ToolResultFollowUps(
    val items: List<JsonObject>,
    val dispositions: List<ImageDisposition>,
)

/** One image's fate: delivered as an input_image part, or omitted for [reason] (the marker's words). */
public data class ImageDisposition(val delivered: Boolean, val reason: String?)

/** DR-164's wording, which ResponsesImageFloorTest pins as the non-floor drop's story. */
private const val UNSUPPORTED_SOURCE = "unsupported source"
