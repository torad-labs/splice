// NEW: where a CLI verb that hit a boot refusal offers the fix session. Off a terminal it offers nothing: the verb
// prints the whole list and exits non-zero, and nothing is changed.
package splice.app.cli

import splice.app.modes.ModeBoot
import splice.app.provider.local.LocalRowBootFindings
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.topology.TopologyBackupName
import splice.core.util.EnvReader
import splice.core.util.SecureFile
import splice.core.util.TopologyRefusal
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// why: the same twelve hex characters the console writer names its backups with (TopologyBackupName).
private const val BACKUP_HASH_LENGTH = 12

internal class ConfigFixOffer(
    private val env: EnvReader = EnvReader(System::getenv),
    private val interactive: Boolean = System.console() != null,
    private val prompter: FixPrompter = TerminalFixPrompter(env),
    private val backups: Path = StatePaths().configBackupsDir,
    /** Where the offer and its session speak: the terminal's error stream, where every refusal line goes. */
    private val output: TerminalOutput = TerminalOutput(System.err::println),
) {
    private val runtimes = LocalRowBootFindings(env)
    private val beside by lazy { ModeBoot().beside(env) }

    /** [broken] as the refusal listing every finding of splice.toml, when the file has findings; null otherwise. */
    fun refusalOf(broken: Throwable): TopologyRefusal? {
        if (broken is TopologyRefusal) return broken
        val text = try {
            Files.readString(TopologyLoader.configPath(env))
        } catch (_: java.io.IOException) {
            return null
        }
        val read = ConfigFindings.read(text, backups, configDir(), runtimes, beside) as? ConfigRead.Refused
        return read?.let { TopologyRefusal(it.findings) }
    }

    /** True when the person fixed every finding, so the verb may run again. False off a terminal, or when they quit. */
    fun offer(): Boolean {
        if (!interactive) return false
        val file = TopologyLoader.configPath(env)
        output.line("splice: a fix session can repair this now; your file is backed up before its first change.")
        return session(file, advisory = false).run()
    }

    /** `splice check-config --fix` (upgrade-compat, Nov 23, 2026): on a terminal, the fix session over every finding,
     *  then over every warning boot serves past (a value a 0.6.4 file left on a head, which moves to its provider), each
     *  move announced and the file backed up first; then the check itself, whose exit it answers. Off a terminal it
     *  says so and only checks. */
    fun fixAll(): Int {
        val check = ConfigPreflight(env, output)
        if (!interactive) {
            output.line("splice: the fix session needs a terminal; run `splice check-config --fix` in one")
            return check.check()
        }
        val file = TopologyLoader.configPath(env)
        if (!Files.exists(file)) return check.check()
        val read = { ConfigFindings.read(Files.readString(file), backups, configDir(), runtimes, beside) }
        val fixed = read() !is ConfigRead.Refused || offer()
        val left = (read() as? ConfigRead.Ready)?.warnings.orEmpty()
        if (fixed && left.isNotEmpty()) {
            output.line("splice: what splice boots past can be fixed too; nothing moves without your answer.")
            session(file, advisory = true).run()
        }
        return check.check()
    }

    /** The fix session on [file] over each read's findings, and with [advisory] over its warnings once it has none. */
    private fun session(file: Path, advisory: Boolean): ConfigFixSession =
        ConfigFixSession(
            file,
            output,
            prompter,
            { text ->
                when (val read = ConfigFindings.read(text, backups, configDir(), runtimes, beside)) {
                    is ConfigRead.Refused -> read.findings
                    is ConfigRead.Ready -> if (advisory) read.warnings else emptyList()
                }
            },
            { source -> backup(source) },
            advisory,
        )

    /** The topology's directory, where a head's relative system_prompt_file is read, as boot reads it. */
    private fun configDir(): Path = TopologyLoader.configPath(env).toAbsolutePath().let { it.parent ?: it }

    private fun backup(source: Path): Path {
        val bytes = Files.readAllBytes(source)
        val hash = TopologyLoader.sha256Hex(bytes).take(BACKUP_HASH_LENGTH)
        val _ = SecureFile.ownerOnlyDirectory(backups)
        val copy = backups.resolve(TopologyBackupName.of(source, Instant.now(), hash))
        SecureFile.writeAtomic0600(copy, bytes.toString(Charsets.UTF_8))
        return copy
    }
}

/** Reads replies from the terminal and runs $VISUAL or $EDITOR on the file. */
internal class TerminalFixPrompter(private val env: EnvReader) : FixPrompter {
    override fun ask(question: String): String? = System.console()?.readLine(question)

    override fun secret(question: String): String? = System.console()?.readPassword(question)?.concatToString()

    override fun edit(file: Path, line: Int?): Boolean {
        val editor = (env("VISUAL") ?: env("EDITOR"))?.takeIf(String::isNotBlank) ?: return false
        val command = editor.split(' ').filter(String::isNotBlank) +
            listOfNotNull(line?.let { "+$it" }, file.toString())
        return ProcessBuilder(command).inheritIO().start().waitFor() == 0
    }
}
