package splice.core.config

/** Effective values and their layer provenance from the same authoritative observation. */
public data class ConfigSnapshot(
    val effective: SpliceConfig,
    val layers: ConfigLayers,
)
