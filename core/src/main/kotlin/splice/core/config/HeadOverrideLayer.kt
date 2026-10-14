// NEW: [heads.<key>.overrides] as the daemon holds them NOW. ConfigService read them once at construction; a
// console write of splice.toml sets them here too, so the next read of that command's config follows the file
// and a knob read live acts without a restart.
package splice.core.config

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

internal fun interface ConfigRevisionChanged {
    operator fun invoke()
}

public class HeadOverrideLayer(boot: Map<String, Map<String, String>>) {
    private val held = AtomicReference(
        boot.mapValues { (_, values) -> Collections.unmodifiableMap(LinkedHashMap(values)) }
            .let(Collections::unmodifiableMap),
    )
    private var changed = ConfigRevisionChanged {}

    internal fun observeChanges(listener: ConfigRevisionChanged) {
        changed = listener
    }

    /** Every command's own knob values, as last read or written. */
    public fun all(): Map<String, Map<String, String>> = held.get()

    /** [head] now declares exactly [overrides]; an empty map is the command with none. */
    public fun set(head: String, overrides: Map<String, String>) {
        val owned = Collections.unmodifiableMap(LinkedHashMap(overrides))
        held.updateAndGet { now ->
            Collections.unmodifiableMap(if (owned.isEmpty()) now - head else now + (head to owned))
        }
        changed()
    }
}
