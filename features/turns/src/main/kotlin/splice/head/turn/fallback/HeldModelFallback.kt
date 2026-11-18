// NEW (Nov 13, 2026): a session's own fallback model, while the model it asks for is held on every login. That evening
// every Fable login ran out one evening and every turn waited until Sunday. A session that names a fallback model on
// its command runs on it while no login can serve the model it asks for, and goes back on the first turn one can.
//
// ALWAYS TO A MODEL WITH ROOM (Nov 17, 2026): a session that names none no longer waits. Asked that afternoon whether
// splice should move a session by itself when its model is spent on every login and another has room, Marcos chose
// always, to whichever model has room. Until then a Fable session waited three days while every login had Opus to give:
// one sat 55 minutes behind Fable's week after its person had already picked another model, since the turn in flight
// still asked for Fable. The session's own fallback is tried first, then the head's declared models in their order,
// then the models the sessions' own turns ask for, then every model asked in the last hour.
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
import splice.upstream.credentials.AccountView

// why: a hold that ends sooner than this is a rate limit the turn waits out on its own login; a spent plan runs days.
private const val PLAN_HOLD_MS = 10 * 60_000L

// why: the request body's field that names the model, the one field a fallback changes.
private const val TURN_BODY_MODEL = "model"

/** The model each session of a head falls back to while the one its turns ask for is held on every login. */
public fun interface SessionFallbacks {
    /** [session]'s fallback model on this head, or null when it named none. */
    public fun of(session: String): String?
}

/** Sends a turn to another model while no login of the head can serve the model it asks for, each held past a rate
 *  limit's wait, and some login can serve the other: the session's own fallback where it named one with room, else the
 *  first model with room, in the order the header gives. Stateless: the first turn a login can serve the asked model
 *  again goes to it. Only the request's `model` changes. Thinking blocks stay as they are, since the API reads or drops
 *  each by the model that reads it, and the reasoning effort is the client's own. */
internal class HeldModelFallback(
    private val quota: HeadDeps.HeadQuota,
    private val catalog: ModelCatalog,
    private val log: LogSink,
    private val now: WallClock = WallClock(System::currentTimeMillis),
) {
    /** [parsed] as it goes upstream for [session]: under another model when the rule above holds. */
    fun applied(parsed: AnthropicTurnBody, session: String?): AnthropicTurnBody {
        val asked = parsed.typed.model
        val instead = instead(asked, session) ?: return parsed
        log(
            "[fallback] session ${LogSafe.str(session ?: "none")}: ${LogSafe.str(asked)} is held on every login; " +
                "sent as ${LogSafe.str(instead)}\n",
        )
        return parsed.copy(
            raw = JsonObject(parsed.raw + (TURN_BODY_MODEL to JsonPrimitive(instead))),
            typed = parsed.typed.copy(model = instead),
        )
    }

    /** The model a turn of [session] asking for [asked] goes on under, or null where it goes as asked. [heldToo]: logins
     *  a turn just met a limit of [asked] on, with its reset in epoch seconds, which the pool has not published yet;
     *  [everyModel] where that limit is a window of the whole login, which holds them for every other model too. */
    fun instead(
        asked: String,
        session: String?,
        heldToo: Map<String, Long> = emptyMap(),
        everyModel: Boolean = false,
    ): String? {
        val pool = quota.activePool ?: return null
        if (!heldEverywhere(pool, asked, heldToo)) return null
        val named = session?.let(quota.sessionFallbacks::of)
        val models = listOfNotNull(named) + catalog.tierModelIds() + pool.asked.own() + pool.asked.lately()
        val out = if (everyModel) heldToo.keys else emptySet()
        return models.distinct().firstOrNull { it != asked && catalog.contains(it) && servable(pool, it, out) }
    }

    /** No login serves [model] now, and every one held for it is held past a rate limit's wait. A login that needs a
     *  sign-in or its owner's act serves nothing and comes back by no reset, so it is left out of both. */
    private fun heldEverywhere(pool: AccountPool, model: String, heldToo: Map<String, Long>): Boolean {
        val view = pool.view(null, catalog.stripSuffixes(model))
        val horizon = (now() + PLAN_HOLD_MS) / 1_000
        val logins = view.accounts.filter(::signedIn)
        val holds = logins.mapNotNull { heldToo[it.label] ?: view.blockedUntilEpochSecondsByLabel[it.label] }
        return logins.none { it.available && it.label !in heldToo } && holds.isNotEmpty() && holds.all { it > horizon }
    }

    private fun signedIn(login: AccountView): Boolean =
        login.credential.let { it.present && it.exclusionReason == null && it.block == null }

    private fun servable(pool: AccountPool, model: String, out: Set<String>): Boolean =
        pool.view(null, catalog.stripSuffixes(model)).accounts.any { it.available && it.label !in out }
}
