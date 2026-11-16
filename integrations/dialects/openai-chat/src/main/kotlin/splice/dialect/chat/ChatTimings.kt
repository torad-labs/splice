// NEW: server mode (docs/specs/server-mode.md, F5: "prefill and decode rates (from llama-server's `timings`)").
// llama-server puts a `timings` object on the frame that finishes a streamed answer, and on a whole answer: how many
// prompt tokens it read and in how long, and how many answer tokens it wrote and in how long. Its own per-second
// figures are taken when it sends them, and worked out from the counts and the milliseconds when it does not. Any
// other server sends no `timings`, and its turns have no speed.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import splice.core.server.TurnSpeed
import java.util.concurrent.TimeUnit

internal object ChatTimings {
    /** The speed [frame] reports, or null when it carries no `timings`. */
    fun of(frame: JsonObject): TurnSpeed? {
        val timings = frame[TIMINGS] as? JsonObject ?: return null
        val prefill = rate(timings, "prompt_per_second", "prompt_n", "prompt_ms")
        val decode = rate(timings, "predicted_per_second", "predicted_n", "predicted_ms")
        return TurnSpeed(prefill, decode).takeIf { prefill != null || decode != null }
    }

    /** Tokens a second: the server's own figure under [perSecond], else [count] over [ms]; null when neither is a
     *  positive number. */
    private fun rate(timings: JsonObject, perSecond: String, count: String, ms: String): Double? {
        val own = number(timings, perSecond)?.takeIf { it > 0.0 }
        val tokens = number(timings, count)
        val millis = number(timings, ms)?.takeIf { it > 0.0 }
        val worked = if (tokens != null && millis != null) tokens * MS_PER_SECOND / millis else null
        return own ?: worked?.takeIf { it > 0.0 }
    }

    private fun number(obj: JsonObject, key: String): Double? = (obj[key] as? JsonPrimitive)?.doubleOrNull
}

private const val TIMINGS = "timings"
private val MS_PER_SECOND = TimeUnit.SECONDS.toMillis(1).toDouble()
