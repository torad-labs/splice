// NEW: one request's item digests, rendered once however many of its readers index the same items.
package splice.core.perf

import kotlinx.serialization.json.JsonElement
import java.util.IdentityHashMap

/** [InputDigest] item digests memoized by element identity for one request's immutable tree.
 *  why: code mode indexes the same borrowed history items several times per turn (the canonical rewrite, each
 *  baseline restore, the extra-content checks, the anchor capture), and every index rendered every item again.
 *  Identity, never equality: an equal-looking element is rendered for itself, so a digest is always its own
 *  element's bytes. Elements must not be mutated while retained, as for JsonElementInterner. A race between two
 *  readers renders an item twice to the same digest. */
public class InputItemDigests {
    private val lock = Any()
    private val known = IdentityHashMap<JsonElement, String>()

    /** One digest per item, in order; only items this memo has not seen are rendered, through one encoder. */
    public fun hexItems(items: List<JsonElement>): List<String> {
        val held = synchronized(lock) { items.map { known[it] } }
        val rendered = InputDigest.hexItems(items.filterIndexed { at, _ -> held[at] == null }).iterator()
        val digests = held.map { it ?: rendered.next() }
        synchronized(lock) {
            items.forEachIndexed { at, item -> known[item] = digests[at] }
        }
        return digests
    }
}
