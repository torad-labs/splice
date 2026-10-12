// NEW: [heads.<key>.overrides] as the daemon holds them NOW. ConfigService read them once at construction; a
// console write of splice.toml sets them here too, so the next read of that command's config follows the file
// and a knob read live acts without a restart.
package splice.core.config

import java.util.concurrent.atomic.AtomicReference

public class HeadOverrideLayer(boot: Map<String, Map<String, String>>) {
    private val held = AtomicReference(boot)

    /** Every command's own knob values, as last read or written. */
    public fun all(): Map<String, Map<String, String>> = held.get()

    /** [head] now declares exactly [overrides]; an empty map is the command with none. */
    public fun set(head: String, overrides: Map<String, String>) {
        held.updateAndGet { now -> if (overrides.isEmpty()) now - head else now + (head to overrides) }
    }
}
