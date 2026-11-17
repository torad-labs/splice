// NEW: server mode (docs/specs/server-mode.md). What the listener serves from, built by ServerMode as it opens one:
// the keys, each person's limits and settings, the heads and the relay to them, the providers' servers, the
// server's books (F5).
package splice.server

import splice.server.budget.BudgetGate
import splice.server.ledger.TurnLedger
import splice.share.ShareRelay
import splice.share.SharedHeads

/** What the listener serves from: the keys it answers, each person's limits and commands, the heads
 *  `[server] commands` names, the relay that hands their turns to those heads, each person's settings, the servers
 *  behind those heads' providers, and the server's books. */
internal data class ServerParts(
    val keys: ServerKeys,
    val limits: PersonLimits,
    val heads: SharedHeads,
    val relay: ShareRelay,
    val people: ServerPeople,
    val upstreams: UpstreamApi,
    val books: ServerBooks,
)

/** The server's books: the ledger every turn leaves its row in, the budgets each turn must be within (F5), and the
 *  tokens of each person's turns that ended in the last minute (F12). */
internal data class ServerBooks(val turns: TurnLedger, val budgets: BudgetGate, val tokens: MinuteTokens)
