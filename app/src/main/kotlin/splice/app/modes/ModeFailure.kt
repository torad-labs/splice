// NEW: a mode's handler that throws (modes/README.md, "The contract"): the model, or the route's caller, reads
// "mode <name> failed: <exception class>", with no message text, since a message can quote anything the mode held.
// Whatever it throws: a mode is code splice did not write, so every exception is the mode's own failure, and only a
// cancellation or an Error goes on up. (Nov 21, 2026: this caught I/O, serialization and illegal-argument failures
// alone, so a handler that threw an IllegalStateException or a NullPointerException was not answered for, and the
// turn or the route failed with it.)
package splice.app.modes

import splice.core.util.Cancellables

/** What a mode's handler came to: its value, or the sentence said of the exception it threw. */
internal sealed class ModeRun<out T> {
    data class Done<T>(val value: T) : ModeRun<T>()

    data class Threw(val said: String) : ModeRun<Nothing>()
}

/** One piece of a mode's own code: its handler answering a call or a route. */
internal fun interface ModeWork<T> {
    fun run(): T
}

internal object ModeFailure {
    fun <T> run(mode: String, work: ModeWork<T>): ModeRun<T> = Cancellables.runCatchingBestEffort { work.run() }
        .fold({ ModeRun.Done(it) }, { ModeRun.Threw("mode $mode failed: ${named(it)}") })

    // ast-grep-ignore: kt-no-reflection-in-production
    private fun named(failure: Throwable): String = failure.javaClass.simpleName.ifEmpty { "an exception" }
}
