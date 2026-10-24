// How a Responses head's reasoning settings are built from config: the values it was started with, plus the reader
// that restates display, replay and summary as the operator's knobs say they are when a turn is built. Effort is
// carried from the start value on purpose: it is part of the prompt-cache key and stays restart-only.
package splice.app.provider

import splice.core.config.SpliceConfig
import splice.dialect.responses.LiveReasoning
import splice.dialect.responses.ReasoningSettings

internal object ReasoningWiring {

    fun settingsOf(cfg: SpliceConfig): ReasoningSettings = ReasoningSettings(
        display = cfg.showReasoning,
        replay = cfg.replayReasoning,
        effort = cfg.effort,
        summary = cfg.summary,
        live = LiveReasoning {
            cfg.current().let { now ->
                ReasoningSettings(now.showReasoning, now.replayReasoning, cfg.effort, now.summary)
            }
        },
    )
}
