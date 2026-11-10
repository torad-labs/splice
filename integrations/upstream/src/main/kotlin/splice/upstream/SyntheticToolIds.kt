// NEW: 2026-11-09 — the one minter of the tool-call ids splice makes up when an upstream call carries none. Every
// dialect's stream fold and the non-stream body share it, so the next fix to the id's shape lands once.
package splice.upstream

import java.util.UUID

/**
 * Ids for tool calls an upstream sent without one, one instance per round.
 *
 * A minted id rides back in every later request as that call's id, and call pairing keeps one call per id. A
 * counter alone restarts each round, so two turns named their first call alike and pairing dropped the later
 * one; the round's random part makes each id unique within the conversation, as a real upstream id is.
 */
public class SyntheticToolIds {

    private val round by lazy { UUID.randomUUID().toString().replace("-", "") }
    private var next = 0

    /** A fresh id, never repeated in this round or, by its random part, in any other. */
    public fun mint(): String = "toolu_synth_${round}_${next++}"
}
