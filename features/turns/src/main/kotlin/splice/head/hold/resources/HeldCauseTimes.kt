package splice.head.hold.resources

import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource

/** A provider-stated quota reset is distinct from its requested backoff or a local hold probe. */
internal object HeldCauseTimes {
    fun quotaResetEpochSeconds(cause: FailureCause?): Long? {
        val held = (cause as? FailureCause.Splice.HoldOutlasted)?.held ?: cause
        val lift = (held as? FailureCause.Provider.Limited)?.lift as? Lift.At
        return lift?.takeUnless { it.source == LiftSource.RETRY_AFTER }?.at?.epochSecond
    }
}
