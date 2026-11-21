// NEW: 2026-11-20 — a head's readable model names, both ways. Each model whose selector Claude's clients would drop
// (ClientModelNames) has one: the head's [ReadableIds] when splice.toml gives it one, else the name ClientModelNames
// makes, so every head has them with nothing set. The head takes a readable name wherever it takes a selector
// (ModelCatalog.unwrap), and the door lists its models under them (DoorModels).
package splice.core.model

/** The readable names of [catalog]'s models: the name of each model, and the model each name stands for. */
public class ReadableNames internal constructor(private val catalog: ModelCatalog) {
    /** Readable name to model, without window hints, for each model whose selector Claude's clients drop. */
    private val models: Map<String, String> by lazy { named() }
    private val names: Map<String, String> by lazy { models.entries.associate { (name, model) -> model to name } }

    /** The id Claude's clients keep for upstream [model]: its readable name, or its selector when they keep that. A
     *  window hint stays on it. */
    public fun nameOf(model: String): String {
        val base = ModelTierSuffix.strip(model)
        return names[base]?.let { it + model.removePrefix(base) } ?: catalog.wrap(model)
    }

    /** Every name a model answers to: its readable name, and its whole one (no lab's name left out) where that is
     *  another, which a client that was given it before the short one still sends. A readable name is never taken
     *  from its own model by another's whole one. */
    private val answering: Map<String, String> by lazy {
        val prefix = catalog.discoveryPrefix
        models.values.associateBy { ClientModelNames.readable(catalog.wrap(it), prefix, short = false) } + models
    }

    /** The upstream model readable name [id] stands for, its window hint kept; null when [id] is none of this head's
     *  readable names. */
    public fun upstreamOf(id: String): String? {
        val base = ModelTierSuffix.strip(id)
        return answering[base]?.let { it + id.removePrefix(base) }
    }

    /** The selector readable name [id] stands for on this head, or null when [id] is none of its readable names. */
    public fun selectorOf(id: String): String? = upstreamOf(id)?.let(catalog::wrap)

    private fun named(): Map<String, String> {
        val prefix = catalog.discoveryPrefix
        val given = catalog.client.readable
        val out = LinkedHashMap<String, String>()
        catalog.models.map { ModelTierSuffix.strip(it.id) }.distinct().forEach { model ->
            val selector = catalog.wrap(model)
            if (ClientModelNames.kept(selector)) return@forEach
            val name = given?.let { it.prefix + it.nameOf(model) } ?: ClientModelNames.readable(selector, prefix)
            // Two models one short name would fit (`kimi-k3` and `k3`) keep the later one's whole name.
            out[if (name in out) ClientModelNames.readable(selector, prefix, short = false) else name] = model
        }
        return out
    }
}
