// NEW: custom-provider (issue #680, cp-login). A command splice starts with /bin/sh -c is a tree: the shell, the script
// it runs, and whatever that starts (a browser sign-in, a sleep, a poll). Killing the shell alone leaves the rest
// re-parented to init and still running, so a cancelled sign-in would finish and sign the person in after all.
package splice.core.process

/** The process [root] started and everything below it. */
public class ProcessTree(private val root: Process) {
    /** Kill the whole tree. Every process below the root is read first, since one whose parent is gone is no longer
     *  below it; then they die parents before children, so no live parent sees its child end and goes on to its next
     *  step (a sign-in script whose wait was killed would otherwise write its signed-in mark). */
    public fun kill() {
        val below = root.descendants().toList().sortedBy(::depth)
        root.destroyForcibly()
        below.forEach { it.destroyForcibly() }
    }

    /** How many parents [process] has between it and the root; read while the root
     *  lives, before anything below it is re-parented. */
    private fun depth(process: ProcessHandle): Int =
        generateSequence(process.parent().orElse(null)) { it.parent().orElse(null) }
            .takeWhile { it.pid() != root.pid() }
            .count()
}
