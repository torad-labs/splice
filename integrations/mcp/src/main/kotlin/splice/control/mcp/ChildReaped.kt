// How a hosted child's exit is reaped. Stdout closing is the child's death, but the kernel reaps it a beat later, so the
// host waits that beat for the exit code to be real. A test holds this wait open to put a replacement child into the
// process slot while the old child's exit is still being handled.
package splice.control.mcp

import java.util.concurrent.TimeUnit

// why: stdout closes a beat before the kernel reaps the child, and a second is far longer than that beat.
private const val EXIT_WAIT_MS = 1_000L

internal fun interface ChildReaped {
    operator fun invoke(child: Process): Boolean

    /** The exit code once reaped, else "unknown". */
    fun codeOf(child: Process): String = if (invoke(child)) child.exitValue().toString() else "unknown"
}

/** The production wait: up to a second for the kernel to reap the child. */
internal val waitForReap = ChildReaped { child -> child.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) }
