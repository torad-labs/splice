// One row of tools/gate/config/ladder.json: the leg's task, its argv and why it exists. Read by LadderTable.
package splice.ladder

class LadderLeg(
    val task: String,
    val command: List<String>,
    val why: String,
    val dependsOn: List<String>,
    val inputs: List<String>,
    val creates: String?,
    val owns: String?,
    val afterAllTests: Boolean,
)
