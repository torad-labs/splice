package splice.provider.codex.stream.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import splice.provider.codex.CodeModeRunContext
import java.util.concurrent.ConcurrentHashMap

/** The capability expires before the request's sink can finish; a native cell retains only its opaque token. */
internal class CodeModeNotificationToken

internal class CodeModeNotificationOwner(
    val token: CodeModeNotificationToken,
    val conversation: String,
    val context: CodeModeRunContext,
    val job: Job,
) {
    val mutex = Mutex()
    val deliveries: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    @Volatile var active = true

    fun retire() {
        active = false
        deliveries.forEach { it.cancel(CancellationException("Notification request owner ended")) }
    }
}
