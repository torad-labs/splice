// NEW: gpt-speed (docs/specs/gpt-speed.md) — the speeds a GPT head signed in with ChatGPT can run a session at, as
// OpenAI names them (developers.openai.com/api/docs/guides/fast-mode and .../ultrafast-mode, read 2026-11-15):
// standard is no `service_tier` at all; Fast is `priority` (or `fast`, for models after GPT-5.6); Ultrafast is
// `ultrafast`. The answer names the tier it was served at, `default` when it ran at standard speed.
package splice.core.speed

/** The Responses field a request asks for a tier in, and the one an answer names the tier it served the turn at. */
public const val SERVICE_TIER: String = "service_tier"

/** A session's speed, by the word a perf row's reader says ([word]) and the name a person is told ([title]).
 *  [planMultiple] is what one turn at this speed uses of a ChatGPT plan's included limits, as OpenAI publishes it; null
 *  at standard. [code] is the number a perf row stores it as, since a row's counters are numbers (SpeedCounters): fixed
 *  per speed, never the enum's position, so a speed added later cannot re-read a stored row. */
public enum class Speed(
    public val word: String,
    public val title: String,
    public val planMultiple: String?,
    public val code: Long,
) {
    STANDARD("standard", "Standard", null, 0L),
    FAST("fast", "Fast", "2.5x", 1L),
    ULTRAFAST("ultrafast", "Ultrafast", "8x", 2L),
}

/** The wire's words for a [Speed], read one way. */
internal object SpeedWords {
    /** The speed a backend's `service_tier` names, asked or answered: `priority` and `fast` are Fast, `ultrafast` is
     *  Ultrafast, `default` and `auto` are standard. Null for no tier, or one splice has no word for. */
    fun served(tier: String?): Speed? = when (tier?.trim()?.lowercase()) {
        "priority", "fast" -> Speed.FAST
        "ultrafast" -> Speed.ULTRAFAST
        "default", "auto", "standard" -> Speed.STANDARD
        else -> null
    }

    /** The speed a perf row or a saved setting names by its [Speed.word], or null. */
    fun named(word: String?): Speed? = Speed.entries.firstOrNull { it.word == word?.trim()?.lowercase() }
}
