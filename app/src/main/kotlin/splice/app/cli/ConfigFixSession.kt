// NEW: the interactive fix session fail-closed boot offers (project law: "the CLI offers an interactive fix
// session; never boot on a finding or fix silently"). splice.toml is copied to splice's backup directory once,
// before the first change; the file is checked again after every fix; the session succeeds only when no finding is
// left. Every change is announced before it is made, and a removed or replaced value is never printed.
package splice.app.cli

import splice.core.terminal.TerminalOutput
import splice.core.topology.TopologyFinding
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** What a person at a terminal is asked, and the editor they can be handed the file in. */
internal interface FixPrompter {
    /** The reply to [question], or null when input has ended. */
    fun ask(question: String): String?

    /** A reply that is NOT echoed to the screen: a value typed to fix a header can be a bearer token, and an echoed
     *  one stays in scrollback and in the terminal's own buffer. */
    fun secret(question: String): String?

    /** Opens [file] in the person's editor at [line]; false when no editor could be run. */
    fun edit(file: Path, line: Int?): Boolean
}

/** The findings splice.toml's [text] has, by the same check boot makes. */
internal fun interface ConfigFindingsOf {
    operator fun invoke(text: String): List<TopologyFinding>
}

/** Where a copy of [file] is kept before the first change; returns the copy's path. */
internal fun interface ConfigBackup {
    operator fun invoke(file: Path): Path
}

internal class ConfigFixSession(
    requested: Path,
    private val output: TerminalOutput,
    private val prompter: FixPrompter,
    private val check: ConfigFindingsOf,
    private val backup: ConfigBackup,
) {
    /** The link's TARGET, never the link: a splice.toml linked into a dotfiles checkout is edited where it lives,
     *  so an atomic write cannot replace the link with a regular file and leave the target unfixed. */
    private val file: Path = try {
        requested.toRealPath()
    } catch (_: java.io.IOException) {
        requested
    }

    private var backedUp = false
    private val skipped = mutableSetOf<String>()

    /** True when the file ends with no finding. */
    fun run(): Boolean {
        var findings = check(Files.readString(file))
        while (findings.isNotEmpty()) {
            val finding = findings.firstOrNull { it.text() !in skipped }
            if (finding == null) {
                output.line("")
                output.line("${findings.size} finding(s) left as they are; splice does not start until fixed.")
                return false
            }
            output.line("")
            output.line("${findings.size} finding(s) left. Fixing: ${finding.text()}")
            val changed = fix(finding) ?: return false
            if (changed) findings = check(Files.readString(file))
        }
        return true
    }

    /** null = the person quit; true = the file changed; false = nothing changed (the same finding is offered again). */
    private fun fix(finding: TopologyFinding): Boolean? {
        val line = finding.line
        val editable = line?.takeIf { assignmentAt(it) != null }
        showMenu(line, editable != null, settable(finding))
        return when (prompter.ask("> ")?.trim()?.lowercase()) {
            "d" -> editable?.let(::removeLine) ?: false
            "v" -> editable?.takeIf { settable(finding) }?.let(::setValue) ?: false
            "e" -> edit(line)
            "s" -> skip(finding)
            "q", null -> null
            else -> false
        }
    }

    /** A value can be set on any finding but an unknown key, which has no value to set. */
    private fun settable(finding: TopologyFinding): Boolean = !finding.message.contains("is not a splice.toml setting")

    private fun skip(finding: TopologyFinding): Boolean {
        skipped.add(finding.text())
        return false
    }

    private fun showMenu(line: Int?, editable: Boolean, settable: Boolean) {
        if (editable) output.line("  [d] remove the line" + if (settable) "  [v] set a new value" else "")
        output.line("  [e] open your editor${line?.let { " at line $it" }.orEmpty()}")
        output.line("  [s] skip this one for now")
        output.line("  [q] quit; splice does not start")
    }

    /** The key assigned on [line], when that line holds one `key = value` assignment. */
    private fun assignmentAt(line: Int): String? = Files.readAllLines(file).getOrNull(line - 1)
        ?.takeIf { it.contains('=') && !it.trimStart().startsWith("#") }
        ?.substringBefore('=')?.trim()

    private fun removeLine(line: Int): Boolean {
        val lines = Files.readAllLines(file)
        backupOnce()
        output.line("removing line $line (${assignmentAt(line)})")
        write(lines.filterIndexed { index, _ -> index != line - 1 })
        return true
    }

    private fun setValue(line: Int): Boolean {
        val key = requireNotNull(assignmentAt(line))
        val typed = prompter.secret("new value for $key (not shown as you type; a bare word is quoted for you)> ")
            ?.trim().orEmpty()
        if (typed.isEmpty()) return false
        val literal = typed.first() in "\"[{-0123456789" || typed == "true" || typed == "false"
        output.line("setting $key on line $line to your value")
        backupOnce()
        val written = if (literal) typed else "\"$typed\""
        val lines = Files.readAllLines(file)
        write(lines.mapIndexed { index, text -> if (index == line - 1) "$key = $written" else text })
        return true
    }

    private fun edit(line: Int?): Boolean {
        backupOnce()
        val opened = prompter.edit(file, line)
        if (!opened) output.line("no editor could be started; set EDITOR and try again")
        return opened
    }

    private fun write(lines: List<String>) {
        backupOnce()
        SecureFile.writeAtomic0600(file, lines.joinToString("\n", postfix = "\n"))
    }

    private fun backupOnce() {
        if (backedUp) return
        output.line("backed up ${file.fileName} to ${backup(file)}")
        backedUp = true
    }
}
