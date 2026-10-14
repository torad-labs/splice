package splice.core.config

import splice.core.util.EnvReader
import java.util.Collections

/** Keeps only one observed generation. ConfigService still owns every layer's precedence. */
internal class ConfigSnapshotCache {
    fun interface HeadRead {
        operator fun invoke(head: String?): SpliceConfig
    }

    class View(
        private val defaults: Map<String, Any?>,
        private val coercion: ConfigCoercion,
        private val layers: ConfigLayers,
        private val read: HeadRead,
        private val testIdleFloor: Boolean,
    ) {
        operator fun invoke(raw: Map<String, Any?>, head: String?): ConfigSnapshot {
            // A held config must not capture this builder and retain every sibling head's old layers.
            val reader = read
            return ConfigSnapshot(
                SpliceConfig(
                    coercion.normalize(defaults + raw, testIdleFloor),
                    FreshConfig { reader(head) },
                    Collections.unmodifiableSet(LinkedHashSet(raw.keys)),
                ),
                layers,
            )
        }
    }

    data class Inputs(
        val file: Map<String, Any?>,
        val env: Map<String, String>,
        val runtime: Map<String, Any?>,
        val heads: Map<String, Map<String, String>>,
        val testIdleFloor: Boolean,
    )

    data class Generation(
        val inputs: Inputs,
        val global: ConfigSnapshot,
        val heads: Map<String, ConfigSnapshot>,
        val envRejected: Map<String, String>,
    ) {
        fun snapshot(head: String?, fresh: FreshConfig): ConfigSnapshot = when {
            head == null -> global
            head in heads -> checkNotNull(heads[head])
            else -> ConfigSnapshot(global.effective.following(fresh), global.layers)
        }
    }

    private val lock = Any()
    private var observation = 0L
    private var held: Generation? = null

    /** Capture alias precedence on every read, without re-coercing an unchanged layer. */
    fun environment(reader: EnvReader): Map<String, String> {
        val data = LinkedHashMap<String, String>()
        for (knob in Knob.entries) {
            val raw = knob.envNames.firstNotNullOfOrNull { name -> reader(name)?.takeIf { it.isNotEmpty() } }
            if (raw != null) data[knob.key] = raw
        }
        return Collections.unmodifiableMap(data)
    }

    /** A newer read or mutation prevents this read from publishing over it. */
    fun begin(): Long = synchronized(lock) { ++observation }

    fun current(): Generation? = synchronized(lock) { held }

    fun publish(ticket: Long, generation: Generation): Generation = synchronized(lock) {
        if (ticket == observation) held = generation
        generation
    }

    fun invalidate() {
        synchronized(lock) {
            observation++
            held = null
        }
    }
}
