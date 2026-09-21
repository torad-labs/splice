// PORT-OF: server/src/config.mjs configLayers/patchConfig RETURN SHAPES @ pre-public-port-baseline
// — behaviourless DTOs. They hold no invariant of their own; the two that matter are held by their
// producer in ConfigService.kt: [ConfigLayers] must report mergedRaw's precedence ORDER (the
// dashboard's provenance surface reads it as truth, so a divergence makes the panel lie), and
// [PatchResult.effective] is the POST-patch view, read after the runtime layer is applied.
// STAYS IN :core (HD-25, 2026-08-18): the only real consumer is the control-plane dashboard and the
// instinct is to move these to splice.control.api. That destination is CLOSED — the module
// direction law forbids :core depending on :control (splice.module-law.gradle.kts), and these are
// what :core hands out, not what it reads back.
package splice.core.config

public data class ConfigLayers(
    val defaults: Map<String, Any?>,
    val headOverrides: Map<String, Any?>,
    val file: Map<String, Any?>,
    val env: Map<String, Any?>,
    val runtime: Map<String, Any?>,
    /** JW-06: headKey -> its [heads.<key>.overrides] knobs, precedence position directly above
     *  the global TOML layer (mergedRaw's real order). Empty when no head overrides anything. */
    val perHead: Map<String, Map<String, Any?>> = emptyMap(),
)

public data class PatchResult(
    val applied: Map<String, Any?>,
    val rejected: Map<String, String>,
    val restartRequired: List<String>,
    val effective: SpliceConfig,
)
