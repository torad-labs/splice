package splice.observability.profile

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

internal object ProfileThreads {
    fun factory(name: String): ThreadFactory {
        val platform = Executors.defaultThreadFactory()
        return ThreadFactory { action ->
            platform.newThread(action).apply {
                this.name = name
                isDaemon = true
            }
        }
    }
}
