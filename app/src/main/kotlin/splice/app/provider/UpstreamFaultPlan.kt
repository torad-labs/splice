package splice.app.provider

import splice.core.turn.WatchdogBudget

/** What a head does when its upstream goes wrong: how long it waits, and the command that fixes the credential. */
internal data class UpstreamFaultPlan(
    val watchdog: WatchdogBudget,
    val loginCommand: String,
)
