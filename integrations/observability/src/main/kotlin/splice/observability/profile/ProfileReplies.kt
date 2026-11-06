package splice.observability.profile

import kotlinx.serialization.json.JsonObject
import splice.core.util.PerfWorkBudget

/** Every public reply has a distinct ownership token; a caller releases it after encoding or copying. */
internal object ProfileReplies {
    fun create(action: ProfileReplyAction): JsonObject {
        val lease = checkNotNull(PerfWorkBudget.acquire(ProfileLimits.METADATA_BYTES)) {
            "Profile reply work budget exhausted"
        }
        var transferred = false
        try {
            return action.read().also {
                lease.retain(it, ProfileLimits.METADATA_BYTES)
                transferred = true
            }
        } finally {
            if (!transferred) lease.close()
        }
    }
}

internal fun interface ProfileReplyAction {
    fun read(): JsonObject
}
