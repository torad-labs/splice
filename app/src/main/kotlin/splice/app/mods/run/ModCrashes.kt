package splice.app.mods.run

import splice.core.util.WallClock
import java.util.concurrent.TimeUnit

private const val CRASH_WINDOW_MINUTES = 10L // why: isolated exits recover; repeated exits within this window do not
private const val FIRST_RETRY_MS = 1000L // why: recover quickly from a one-off crash
private const val SECOND_RETRY_MS = 5000L // why: let a temporary dependency recover
private const val THIRD_RETRY_MS = 25_000L // why: limit resource churn before the last attempt

internal class ModCrashes(private val clock: WallClock) {
    private val delays = listOf(FIRST_RETRY_MS, SECOND_RETRY_MS, THIRD_RETRY_MS)
    private val window = TimeUnit.MINUTES.toMillis(CRASH_WINDOW_MINUTES)
    private var exits = emptyList<Long>()
    private var restarts = emptyList<Long>()

    @Synchronized
    fun record(): Long? {
        val now = clock()
        exits = exits.filter { it > now - window } + now
        return delays.getOrNull(exits.lastIndex)
    }

    @Synchronized
    fun restarted() {
        val now = clock()
        restarts = restarts.filter { it > now - window } + now
    }

    @Synchronized
    fun restartsInWindow(): Int = restarts.count { it > clock() - window }
}
