// NEW: door-default (docs/specs/door-default.md, decision 9). A request a head hands to another head: `claude` on its
// head carries the door's models, so the head keyed `claude` reads the model each request names, and one that names
// another head's model (a selector) is answered by that head, as the door answers it. The head knows only this port:
// which names another head claims, and the answer that head gives. The daemon says both (app/desktop/ClaudeHeadLane);
// every other head, and a head built outside the daemon, claims nothing, and serves every request itself as before.
package splice.head

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import splice.upstream.credentials.AccountPool

/** A request as the head read it: the model it names, its body, its session, and whether it is a `count_tokens`. */
public data class OtherAsk(
    val model: String,
    val request: JsonObject,
    val session: String?,
    val counting: Boolean,
)

/** The answer another head gives a request this head handed it: relayed there, or refused by name. */
public fun interface OtherAnswer {
    public suspend fun answer(call: ApplicationCall)
}

/** Whether a turn through a door, which carries no Claude login of anyone's, may run on the pool's login [label].
 *  Since Nov 17, 2026 the door runs Claude's models on the accounts added to splice and never on the
 *  person's own Claude login, so a login that forwards the caller's sign-in or reads it from the person's own Claude
 *  folder is never one; every other login is. Another machine's turn (LAN share) is no door's, and keeps its rules. */
public fun interface HeldLogins {
    public fun held(label: String): Boolean

    /** The logins of [pool] a turn through [door] never runs on: every one not [held]. None for a turn through no door. */
    public fun never(pool: AccountPool, door: String?): Set<String> =
        if (door == null) emptySet() else pool.members.map { it.label }.filterNot(::held).toSet()
}

/** Which requests another head answers in this head's place. */
public fun interface OtherHeads {
    /** The answer to [ask] when another head answers it, or null when this head serves it. Asked once per request,
     *  before anything of it is built or sent: a claim is the model's name alone, never a wait. */
    public fun claim(ask: OtherAsk): OtherAnswer?
}
