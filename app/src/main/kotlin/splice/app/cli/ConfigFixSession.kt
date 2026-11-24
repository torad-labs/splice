// NEW: the interactive fix session fail-closed boot offers (design rule: "the CLI offers an interactive fix
// session; never boot on a finding or fix silently"). splice.toml is copied to splice's backup directory once,
// before the first change; the file is checked again after every fix; the session succeeds only when no finding is
// left. Every change is announced before it is made, and a removed or replaced value is never printed.
// Failure-handling slice 4: a retired knob can also be set outside splice.toml. One saved in splice's state folder is
// cleared there, after the same one backup; a variable in the environment is named to unset where it is set, which
// no session can do for the shell or the service it runs under.
package splice.app.cli

import splice.core.config.SavedSettings
import splice.core.terminal.TerminalOutput
import splice.core.topology.GatewayMove
import splice.core.topology.TopologyFileLock
import splice.core.topology.TopologyFinding
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** What the session says of a saved-settings file a running daemon wrote to between its read and the clear. */
private const val NOT_CLEARED = "changed meanwhile; nothing was cleared, and its findings are read again"

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
    /** A session over warnings splice boots past (`check-config --fix`), not findings that keep it from starting. */
    private val advisory: Boolean = false,
) {
    /** What quitting, or leaving one as it is, means for splice's start. */
    private val leaving: String = if (advisory) "splice starts as it is" else "splice does not start"

    /** What the session works through. */
    private val noun: String = if (advisory) "warning(s)" else "finding(s)"

    /** The link's TARGET, never the link: a splice.toml linked into a dotfiles checkout is edited where it lives,
     *  so an atomic write cannot replace the link with a regular file and leave the target unfixed. */
    private val file: Path = try {
        requested.toRealPath()
    } catch (_: java.io.IOException) {
        requested
    }

    private var backedUp = false
    private val skipped = mutableSetOf<String>()

    /** The saved-settings files already copied to the backups, once each before their first clear. */
    private val savedBackedUp = mutableSetOf<Path>()

    /** The bytes the findings on offer were read from: an edit lands only on these, since its line and key were
     *  found in them (PR #247's sixth review). */
    private var checked = ""

    /** True when the file ends with no finding. */
    fun run(): Boolean {
        checked = Files.readString(file)
        var findings = check(checked)
        while (findings.isNotEmpty()) {
            val finding = findings.firstOrNull { it.text() !in skipped }
            if (finding == null) {
                output.line("")
                output.line("${findings.size} $noun left as they are; $leaving.")
                return false
            }
            output.line("")
            output.line("${findings.size} $noun left. Fixing: ${finding.text()}")
            val changed = fix(finding) ?: return false
            if (changed) {
                checked = Files.readString(file)
                findings = check(checked)
            }
        }
        return true
    }

    /** null = the person quit; true = a file changed; false = nothing changed (the same finding is offered again). A
     *  variable set in the environment this session runs in can be unset only by the shell profile or service that
     *  sets it, so the session names it and where it was read, and moves on to the next finding. */
    private fun fix(finding: TopologyFinding): Boolean? = when (val origin = finding.origin) {
        is TopologyFinding.Origin.Environment -> {
            output.line("  ${origin.name} is set in the environment this command runs in. Run `unset ${origin.name}`,")
            output.line("  and remove it from the shell profile or service that sets it, then start splice again.")
            skip(finding)
        }
        is TopologyFinding.Origin.Saved -> clearOffered(finding, origin)
        is TopologyFinding.Origin.Mode -> {
            output.line("  mode ${origin.name} is part of splice's own build: it is fixed in its folder,")
            output.line("  modes/${origin.name}, and a new build of splice.")
            skip(finding)
        }
        TopologyFinding.Origin.File -> fixInFile(finding)
    }

    /** A setting splice saved in its state folder: cleared there on [c], the rest of the saved settings kept. The saved
     *  file is copied to the backups once, before its first clear. A file a running daemon saved to meanwhile is left
     *  as it is, and its findings are read again. */
    private fun clearOffered(finding: TopologyFinding, origin: TopologyFinding.Origin.Saved): Boolean? {
        output.line("  [c] clear the saved setting from ${origin.file}")
        output.line("  [s] skip this one for now")
        output.line("  [q] quit; $leaving")
        return when (prompter.ask("> ")?.trim()?.lowercase()) {
            "c" -> {
                val saved = origin.file
                if (savedBackedUp.add(saved)) output.line("backed up ${saved.fileName} to ${backup(saved)}")
                val cleared = SavedSettings(saved).clear(origin.key)
                output.line(if (cleared) "cleared ${origin.key} from $saved" else "${saved.fileName} $NOT_CLEARED")
                true
            }
            "s" -> skip(finding)
            "q", null -> null
            else -> false
        }
    }

    private fun fixInFile(finding: TopologyFinding): Boolean? {
        val line = finding.line
        val editable = line?.takeIf { assignmentAt(it) != null }
        val offers = FixOffers(finding, editable != null)
        showMenu(line, offers)
        return when (val answer = prompter.ask("> ")?.trim()?.lowercase()) {
            "e" -> edit(line)
            "s" -> skip(finding)
            "q", null -> null
            else -> changed(answer, offers, line)
        }
    }

    /** A change to the file the person chose from [offers], or false when the answer is not one that is on offer. */
    private fun changed(answer: String, offers: FixOffers, line: Int?): Boolean = when {
        answer !in offers.letters -> false
        answer == "m" -> moveValue(requireNotNull(offers.move), line)
        line == null -> false
        answer == "d" -> removeLine(line)
        else -> setValue(line)
    }

    private fun skip(finding: TopologyFinding): Boolean {
        skipped.add(finding.text())
        return false
    }

    private fun showMenu(line: Int?, offers: FixOffers) {
        val choices = listOfNotNull(
            offers.move?.let { move ->
                if (move.companions.isEmpty()) {
                    "[m] move it to [${move.table}], where the gateway reads it"
                } else {
                    "[m] move ${move.named} to [${move.table}] together, where the gateway reads them as one"
                }
            },
            "[d] remove the line".takeIf { offers.removable },
            "[v] set a new value".takeIf { offers.settable },
        )
        if (choices.isNotEmpty()) output.line("  " + choices.joinToString("  "))
        output.line("  [e] open your editor${line?.let { " at line $it" }.orEmpty()}")
        output.line("  [s] skip this one for now")
        output.line("  [q] quit; $leaving")
    }

    /** The key assigned on [line] of the bytes the findings came from ([checked]), when that line holds one
     *  `key = value` assignment. Read from them, not the file, so a save in between neither breaks the menu nor
     *  hands an edit another line's key; [rewrite] then finds the file changed and writes nothing (seventh review). */
    private fun assignmentAt(line: Int): String? = checked.lines().getOrNull(line - 1)
        ?.takeIf { it.contains('=') && !it.trimStart().startsWith("#") }
        ?.substringBefore('=')?.trim()

    /** [move]'s value put where the gateway reads it, and, unless the head keeps it, taken off its line, while the file
     *  still holds the bytes the finding came from ([rewrite]). */
    private fun moveValue(move: GatewayMove, line: Int?): Boolean {
        val moved = MoveFix(check, checked).candidate(move, line)
        if (moved == null) {
            output.line("  that value cannot be moved here without risking splice.toml; edit it, or move it yourself")
            return false
        }
        backupOnce()
        output.line("moving ${move.named} to [${move.table}]")
        rewrite { moved }
        return true
    }

    private fun removeLine(line: Int): Boolean {
        backupOnce()
        output.line("removing line $line (${assignmentAt(line)})")
        rewrite { lines -> lines.filterIndexed { index, _ -> index != line - 1 } }
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
        rewrite { lines -> lines.mapIndexed { index, text -> if (index == line - 1) "$key = $written" else text } }
        return true
    }

    private fun edit(line: Int?): Boolean {
        backupOnce()
        val opened = prompter.edit(file, line)
        if (!opened) output.line("no editor could be started; set EDITOR and try again")
        return opened
    }

    /** [change] applied to the file under the lock the console's and `splice add`'s saves take, and only while the
     *  file still holds the bytes the finding came from: a save that moved a line while he typed would put this
     *  edit on another setting, so nothing is written, and the findings are read again from what is there now. An
     *  edit one of them had prepared from the older bytes is refused at its own re-read instead. */
    private inline fun rewrite(change: (List<String>) -> List<String>) {
        backupOnce()
        TopologyFileLock.held(file) {
            val now = Files.readString(file)
            if (now != checked) {
                output.line("${file.fileName} changed meanwhile; nothing was written, and its findings are read again")
            } else {
                val lines = now.lines().let { if (now.endsWith("\n")) it.dropLast(1) else it }
                SecureFile.writeAtomic0600(file, change(lines).joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun backupOnce() {
        if (backedUp) return
        output.line("backed up ${file.fileName} to ${backup(file)}")
        backedUp = true
    }
}
