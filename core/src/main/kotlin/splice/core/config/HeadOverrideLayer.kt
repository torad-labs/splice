// NEW: [heads.<key>.overrides] as the daemon holds them NOW. ConfigService read them once at construction; a
// console write of splice.toml sets them here too, so the next read of that command's config follows the file
// and a knob read live acts without a restart.
package splice.core.config

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** A published per-head override change invalidates config snapshots, not account or stream state. */
internal fun interface ConfigRevisionChanged {
    operator fun invoke()
}

/** [boot] is each layer's own knobs. [inherited] is what a head takes from its provider beneath its own: the layer a head
 *  reads is its provider's with its own over it, so a value written once on the provider serves every head on it. */
public class HeadOverrideLayer(
    boot: Map<String, Map<String, String>>,
    private val inherited: Map<String, Map<String, String>> = emptyMap(),
) {
    private val held = AtomicReference(
        boot.mapValues { (_, values) -> Collections.unmodifiableMap(LinkedHashMap(values)) }
            .let(Collections::unmodifiableMap),
    )
    private var changed = ConfigRevisionChanged {}

    internal fun observeChanges(listener: ConfigRevisionChanged) {
        changed = listener
    }

    /** Every layer as it is read: its own knobs over what it inherits. */
    public fun all(): Map<String, Map<String, String>> {
        val own = held.get()
        if (inherited.isEmpty()) return own
        return (inherited.keys + own.keys).associateWith { inherited[it].orEmpty() + own[it].orEmpty() }
    }

    /** Every layer's own knob values, as last read or written, without what it inherits. */
    public fun own(): Map<String, Map<String, String>> = held.get()

    /** [head] now declares exactly [overrides]; an empty map is the command with none. */
    public fun set(head: String, overrides: Map<String, String>) {
        val owned = Collections.unmodifiableMap(LinkedHashMap(overrides))
        held.updateAndGet { now ->
            Collections.unmodifiableMap(if (owned.isEmpty()) now - head else now + (head to owned))
        }
        changed()
    }
}

/** The layer name a provider's `[providers.<key>.overrides]` is held under, apart from every head key: the gateway reads
 *  a provider's own knobs through it, and a head takes them beneath its own. */
public const val PROVIDER_LAYER_PREFIX: String = "providers."
