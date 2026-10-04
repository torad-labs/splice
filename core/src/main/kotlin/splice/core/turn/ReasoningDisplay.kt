// NEW: showReasoning tri-state as a TYPE (#924 Phase 2, tier-1). It was a raw String compared to
// "off"/"text"/"thinking" at 20+ sites — a typo or a fourth string was a silent misbehaviour. The
// enum makes the states exhaustive and the comparisons compile-checked; from() tolerates a legacy
// or malformed config value by falling back to OFF (never throws on a typo).
package splice.core.turn

public enum class ReasoningDisplay {
    OFF,
    TEXT,
    THINKING,
    ;

    public val isOff: Boolean get() = this == OFF
}

/** The tolerant config-string reader for [ReasoningDisplay]. A named object since the 2026-08-16
 *  style migration (HD-M8) made the companion illegal; same name, same table, same fallback to OFF
 *  for a legacy or malformed value (never a throw on a typo). */
public object ReasoningDisplayParser {

    public fun from(raw: String?): ReasoningDisplay = when (raw?.lowercase()) {
        "text" -> ReasoningDisplay.TEXT
        "thinking" -> ReasoningDisplay.THINKING
        else -> ReasoningDisplay.OFF
    }
}
