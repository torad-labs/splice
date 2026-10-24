// NEW: Oct 13, 2026 — split from HeadRemoval.kt: the turns routes' lookup for commands removed from Models. It is a
// turns adapter, not part of the removal, so it reads the retired list on its own.
package splice.app.head

import splice.core.retired.RetiredHeads
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource

/** The turns routes' head lookup, extended with the commands removed from Models: their trace files stay in place, so a
 *  removed command's requests open and its prompts and answers search, as they did while it ran. */
internal class RetiredTurnsHeads(
    private val retired: RetiredHeads,
    private val running: TurnsHeadLookup,
) : TurnsHeadLookup {
    override fun byName(name: String): List<TurnsHead> = running.byName(name).ifEmpty {
        retired.all().filter { it.key == name || it.command == name }.map { head ->
            TurnsHead(
                head.key,
                object : HeadCompactSource {
                    override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
                },
            )
        }
    }
}
