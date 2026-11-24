// NEW: server mode (docs/specs/server-mode.md). What the listener serves from, built by ServerMode as it opens one:
// the keys, each person's limits and settings, the heads and what carries a turn to them or to a provider, the
// providers' servers, the server's books (F5).
package splice.server

import splice.core.server.ServerModRoutes
import splice.server.budget.BudgetGate
import splice.server.ledger.TurnLedger
import splice.share.ShareRelay
import splice.share.SharedHeads

/** What the listener serves from: the keys it answers, each person's limits and commands, the heads
 *  `[server] commands` names, the carriers of their turns (the relay to those heads, the gateway to providers), each
 *  person's settings, the servers behind those heads' providers, and the server's books. */
internal data class ServerParts(
    val keys: ServerKeys,
    val limits: PersonLimits,
    val heads: SharedHeads,
    val carriers: TurnCarriers,
    val people: ServerPeople,
    val upstreams: UpstreamApi,
    val books: ServerBooks,
)

/** What carries a turn once it is admitted: the relay that hands it to a head, and the gateway that serves it on a
 *  provider with no head, once the daemon opened it (null before). */
internal data class TurnCarriers(val relay: ShareRelay, val gateway: TeamGateway? = null)

/** The server's books: the ledger every turn leaves its row in, the budgets each turn must be within (F5), and the
 *  tokens of each person's turns that ended in the last minute (F12). */
internal data class ServerBooks(val turns: TurnLedger, val budgets: BudgetGate, val tokens: MinuteTokens)

/** The listener's optional sign-ins and the modes the daemon offers under `/server/mod/...`. */
internal data class OptionalRoutes(val signins: ListenerSignins, val mods: ServerModRoutes?)
