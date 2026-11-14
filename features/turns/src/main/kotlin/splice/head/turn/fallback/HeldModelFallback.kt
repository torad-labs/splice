// NEW (Nov 13, 2026): a session's own fallback model, while the model it asks for is held on every login. That evening
// every Fable login on one machine ran out at about 6:24 PM CT and every turn waited until Sunday. A session that names
// a fallback model on its command runs on it while no login can serve the model it asks for, and goes back on the
// first turn one can. A session that names none keeps waiting as before: splice never picks a model for a session.
package splice.head.turn.fallback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.model.ModelCatalog
import splice.core.parse.AnthropicTurnBody
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.HeadDeps
import splice.upstream.credentials.AccountPool

// why: a hold that ends sooner than this is a rate limit the turn waits out on its own login; a spent plan runs days.
private const val PLAN_HOLD_MS = 10 * 60_000L

// why: the request body's field that names the model, the one field a fallback changes.
private const val TURN_BODY_MODEL = "model"

/** The model each session of a head falls back to while the one its turns ask for is held on every login. */
public fun interface SessionFallbacks {
    /** [session]'s fallback model on this head, or null when it named none. */
    public fun of(session: String): String?
}

/** Sends a session's turn to the fallback model it named while no login of the head can serve the model it asks for,
 *  each held past a rate limit's wait, and some login can serve the fallback. Stateless: the first turn a login can
 *  serve the asked model again goes to it. Only the request's `model` changes. Thinking blocks stay as they are, since
 *  the API reads or drops each by the model that reads it, and the reasoning effort is the client's own. */
internal class HeldModelFallback(
    private val quota: HeadDeps.HeadQuota,
    private val catalog: ModelCatalog,
    private val log: LogSink,
    private val now: WallClock = WallClock(System::currentTimeMillis),
) {
    /** [parsed] as it goes upstream for [session]: with the session's fallback model when the rule above holds. */
    fun applied(parsed: AnthropicTurnBody, session: String?): AnthropicTurnBody {
        val asked = parsed.typed.model
        val pool = quota.activePool ?: return parsed
        val instead = session?.let(quota.sessionFallbacks::of)
            ?.takeIf { it != asked && catalog.contains(it) && heldEverywhere(pool, asked) && servable(pool, it) }
            ?: return parsed
        log(
            "[fallback] session ${LogSafe.str(session)}: ${LogSafe.str(asked)} is held on every login; " +
                "sent as ${LogSafe.str(instead)}\n",
        )
        return parsed.copy(
            raw = JsonObject(parsed.raw + (TURN_BODY_MODEL to JsonPrimitive(instead))),
            typed = parsed.typed.copy(model = instead),
        )
    }

    /** No login serves [model] now, and every one held for it is held past a rate limit's wait. */
    private fun heldEverywhere(pool: AccountPool, model: String): Boolean {
        val view = pool.view(null, catalog.stripSuffixes(model))
        val horizon = (now() + PLAN_HOLD_MS) / 1_000
        val holds = view.blockedUntilEpochSecondsByLabel.values
        return view.accounts.none { it.available } && holds.isNotEmpty() && holds.all { it > horizon }
    }

    private fun servable(pool: AccountPool, model: String): Boolean =
        pool.view(null, catalog.stripSuffixes(model)).accounts.any { it.available }
}
