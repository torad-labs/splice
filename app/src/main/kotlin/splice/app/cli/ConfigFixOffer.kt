// NEW: where a CLI verb that hit a boot refusal offers the fix session. Off a terminal it offers nothing: the verb
// prints the whole list and exits non-zero, and nothing is changed.
package splice.app.cli

import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.topology.TopologyBackupName
import splice.core.util.EnvReader
import splice.core.util.TopologyRefusal
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

// why: the same twelve hex characters the console writer names its backups with (TopologyBackupName).
private const val BACKUP_HASH_LENGTH = 12

internal class ConfigFixOffer(
    private val env: EnvReader = EnvReader(System::getenv),
    private val interactive: Boolean = System.console() != null,
    private val prompter: FixPrompter = TerminalFixPrompter(env),
    private val backups: Path = StatePaths().configBackupsDir,
) {
    /** [broken] as the refusal listing every finding of splice.toml, when the file has findings; null otherwise. */
    fun refusalOf(broken: Throwable): TopologyRefusal? {
        if (broken is TopologyRefusal) return broken
        val text = try {
            Files.readString(TopologyLoader.configPath(env))
        } catch (_: java.io.IOException) {
            return null
        }
        val read = ConfigFindings.read(text, backups) as? ConfigRead.Refused
        return read?.let { TopologyRefusal(it.findings) }
    }

    /** True when the person fixed every finding, so the verb may run again. False off a terminal, or when they quit. */
    fun offer(): Boolean {
        if (!interactive) return false
        val file = TopologyLoader.configPath(env)
        System.err.println("splice: a fix session can repair this now; your file is backed up first.")
        val session = ConfigFixSession(
            file,
            TerminalOutput(System.err::println),
            prompter,
            { text -> (ConfigFindings.read(text, backups) as? ConfigRead.Refused)?.findings.orEmpty() },
            { source -> backup(source) },
        )
        return session.run()
    }

    private fun backup(source: Path): Path {
        val bytes = Files.readAllBytes(source)
        val hash = TopologyLoader.sha256Hex(bytes).take(BACKUP_HASH_LENGTH)
        Files.createDirectories(backups)
        val copy = backups.resolve(TopologyBackupName.of(source, Instant.now(), hash))
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING)
        return copy
    }
}

/** Reads replies from the terminal and runs $VISUAL or $EDITOR on the file. */
internal class TerminalFixPrompter(private val env: EnvReader) : FixPrompter {
    override fun ask(question: String): String? = System.console()?.readLine(question)

    override fun edit(file: Path, line: Int?): Boolean {
        val editor = (env("VISUAL") ?: env("EDITOR"))?.takeIf(String::isNotBlank) ?: return false
        val command = editor.split(' ').filter(String::isNotBlank) +
            listOfNotNull(line?.let { "+$it" }, file.toString())
        return ProcessBuilder(command).inheritIO().start().waitFor() == 0
    }
}
