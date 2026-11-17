// NEW: the harness (docs/specs/harness.md, decisions 4 and 6). How a worker's work ended, and the lines it answers in.
package splice.head.harness

import splice.core.parse.lenientJson
import splice.core.util.JsonScalars

/** How one call of a worker ended. */
internal sealed class WorkerEnd {
    /** The text a result carries. */
    data class Result(val text: String) : WorkerEnd()

    /** The worker refused the turn, with its own [reason]. */
    data class Refusal(val reason: String) : WorkerEnd()

    /** The work could not finish: [why] says what went wrong, in words that never hold a key. */
    data class Failed(val why: String) : WorkerEnd()
}

/** One line of a worker's answer: a progress message, or the line that ends the work. */
internal sealed class WorkerLine {
    data class Progress(val message: String) : WorkerLine()

    data class Ended(val end: WorkerEnd) : WorkerLine()
}

/** Reads the JSON lines a worker answers in: `progress`, then one `result` or one `refusal`. Any other line is a
 *  failure. */
internal class WorkerLines {
    fun read(line: String): WorkerLine {
        val obj = JsonScalars.objectOrNull(lenientJson, line.trim())
        val type = JsonScalars.str(obj, "type")
        val text = JsonScalars.str(obj, "text")
        val reason = JsonScalars.str(obj, "reason")
        val message = JsonScalars.str(obj, "message")
        return when {
            type == "result" && text != null -> WorkerLine.Ended(WorkerEnd.Result(text))
            type == "refusal" && reason != null -> WorkerLine.Ended(WorkerEnd.Refusal(reason))
            type == "progress" && message != null -> WorkerLine.Progress(message)
            obj == null -> WorkerLine.Ended(WorkerEnd.Failed("it sent a line that is not JSON"))
            else -> WorkerLine.Ended(WorkerEnd.Failed("it sent a line that is not progress, result or refusal"))
        }
    }

    /** A worker's body read to its end with no line that ended the work. */
    fun unended(): WorkerEnd = WorkerEnd.Failed("it ended with no result")
}
