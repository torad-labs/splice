// NEW: 2026-10-01 — the modes `[providers.X.quirks] summary_delivery` accepts. Its own file because
// QuirksConfig.kt is the quirk-key denominator (QuirksKeysDocumentedLawTest counts every @SerialName
// there as a key), and these two are values, not keys.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** stream_options.reasoning_summary_delivery on an openai-responses head; [wire] is its TOML and wire spelling. */
@Serializable
public enum class SummaryDelivery(public val wire: String) {
    /** codex-rs's one delivery mode: a summary section still being written when the reasoning item
     *  ends is cancelled (openai/codex#31306). */
    @SerialName("sequential_cutoff")
    SEQUENTIAL_CUTOFF("sequential_cutoff"),

    /** Omit the field: the backend's default delivery, and codex-rs's own default (its
     *  ConcurrentReasoningSummaries flag is off). */
    @SerialName("off")
    OFF("off"),
}
