// V4-179: one renderer for tool-result follow-ups used by text-only output profiles and Codex code mode.
//
// Public Responses profiles with structured tool output emit ordered parts directly. A profile without
// that capability keeps function_call_output textual and uses follow-up user items for supported images;
// code-mode script results are text-only too. Unsupported and below-floor images receive explicit markers.
// Codex persists these items and recognizes replayed history by byte equality with its prior output.
// This stays pure: a ToolResultBlock in, ordered items and image dispositions out.
//
// PUBLIC for :providers-codex; the ordinary Responses builder also consumes it as a profile fallback.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import splice.core.wire.ImageBlock
import splice.core.wire.ToolResultBlock
import splice.dialect.responses.ResponsesQuirks

public class ResponsesToolResultMedia(private val quirks: ResponsesQuirks) {

    private val parts = ResponsesInputParts(quirks.request.minImageEdgePx)

    /** The follow-up items for [block]'s images when tool output must stay textual: readable,
     *  above-floor images ride in an image message, and unsupported-source or floor markers name
     *  omissions. [ToolResultFollowUps.dispositions] preserves one verdict per image in content order. */
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
