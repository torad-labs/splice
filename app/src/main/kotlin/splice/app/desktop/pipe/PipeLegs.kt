// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decisions 3 and 5). Starts the program a Code tab
// session runs on for a command line: Claude Code as Desktop named it, or, for an Antigravity model, splice's own
// `desktop acp` standing in for it, so Claude Code never runs agy's turns and Desktop's request never reaches the door
// as a model it does not serve. Each is started with the session's environment ([LegEnvironment]), its standard input
// a pipe and its output Desktop's own.
package splice.app.desktop.pipe

import java.nio.file.Path

/** Sets up the environment a session's program starts with: the side-by-side variables taken out, and for Claude Code
 *  ([claudeCode]) the desktop key put in where the launch is on splice's door. The translator is given no key. */
internal fun interface LegEnvironment {
    fun apply(environment: MutableMap<String, String>, claudeCode: Boolean)
}

/** Starts programs: [acp] is the command that runs `splice desktop acp`, [environment] sets up each one's, and
 *  [sessions] is where Claude Code keeps the records of running sessions. */
internal class PipeLegs(
    private val acp: List<String>,
    private val environment: LegEnvironment,
    private val sessions: Path,
) {
    /** The program for [line], started; throws [java.io.IOException] when it could not be. */
    fun start(line: ClaudeLine): PipeLeg {
        val command = if (line.antigravity) acp + line.acpArgs() else line.words + line.args
        val builder = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        environment.apply(builder.environment(), claudeCode = !line.antigravity)
        return PipeLeg(builder.start(), sessions)
    }
}
