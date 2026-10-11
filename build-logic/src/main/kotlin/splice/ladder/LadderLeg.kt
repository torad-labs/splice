// One row of tools/gate/config/ladder.json: the leg's task, its argv and why it exists. Read by LadderTable.
package splice.ladder

import java.io.File

private const val APP_JAR_TASK = ":app:shadowJar"

/** [fresh] marks a leg whose verdict is not a function of the files (commit history, a live advisory feed): it runs every time. */
class LadderLeg(
    val task: String,
    val command: List<String>,
    val why: String,
    val dependsOn: List<String>,
    val afterAllTests: Boolean,
    val fresh: Boolean,
    val files: LegFiles,
) {
    /** A leg that depends on the fat jar reads it. Its jar and the files its input globs name decide whether it reruns, unless it is [fresh]. */
    fun readsJar(): Boolean = APP_JAR_TASK in dependsOn
}

/** What a leg reads and writes on disk: the globs it reads, the directory it needs and the one file it owns. */
class LegFiles(val inputs: List<String>, private val creates: String?, private val owns: String?) {
    /** Makes the leg's directory and clears the file it owns, so a rerun never finds its own previous output. */
    fun prepare(root: File) {
        creates?.let { root.resolve(it).mkdirs() }
        owns?.let { OwnedFile(root.resolve(it).toPath()).release() }
    }
}
