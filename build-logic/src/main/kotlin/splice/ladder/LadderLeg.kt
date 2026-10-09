// One row of tools/gate/config/ladder.json: the leg's task, its argv and why it exists. Read by LadderTable.
package splice.ladder

import java.io.File

private const val APP_JAR_TASK = ":app:shadowJar"

class LadderLeg(
    val task: String,
    val command: List<String>,
    val why: String,
    val dependsOn: List<String>,
    val afterAllTests: Boolean,
    val files: LegFiles,
) {
    /** A leg that depends on the fat jar reads it, so the jar and the row's input globs decide whether the leg reruns. */
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
