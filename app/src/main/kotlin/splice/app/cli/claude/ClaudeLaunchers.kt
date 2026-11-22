// NEW: head-catalog (gw-check). Every launcher on PATH that starts Claude Code, read as text and never run: a regular
// file of at most 64 KiB that starts `#!`, in a PATH folder other than the install's bin folder, which is not splice's
// own launcher, and whose text runs `claude` or another router in front of Claude Code (ClaudeLauncherText). Each is
// said to go past the Splice gateway with what sends it there, and with the Claude folder it names in
// CLAUDE_CONFIG_DIR, whose model lists the check reads too. The files are the person's: nothing here edits them.
package splice.app.cli.claude

import splice.client.ClaudeLauncherText
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.core.util.PathProbe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/** One launcher: its [path], whether it goes through the Splice [gateway], [why] it does not, and the Claude
 *  [configDir] it names, when it names one. */
internal data class Launcher(val path: Path, val gateway: Boolean, val why: String?, val configDir: Path?)

internal class ClaudeLaunchers(private val env: EnvReader, private val bin: Path, private val shim: Path) {
    private val folders: List<Path> = env("PATH").orEmpty().split(':').filter { it.isNotBlank() }
        .mapNotNull(::pathOrNull).distinct().filter { it.isDirectory() && !same(it, bin) }

    /** The `claude` a launcher's plain `claude` runs: the first one on PATH. */
    val claude: Path? = env("PATH").orEmpty().split(':').filter { it.isNotBlank() }.mapNotNull(::pathOrNull)
        .map { it.resolve(CLAUDE) }.firstOrNull { it.isRegularFile() && it.isExecutable() }

    /** Every launcher on PATH that starts Claude Code, in PATH order. */
    fun all(): List<Launcher> = folders.flatMap { folder ->
        listed(folder).mapNotNull(::launcher)
    }

    private fun launcher(file: Path): Launcher? {
        val reading = file.takeIf { it.isRegularFile() && !same(it, shim) }?.let(::scriptText)
            ?.let(ClaudeLauncherText::read) ?: return null
        val onPath = claude
        val elsewhere = reading.paths.filter { path ->
            onPath == null || pathOrNull(expanded(path))?.let { same(it, onPath) } != true
        }
        val why = listOfNotNull(
            reading.sets.takeIf { it.isNotEmpty() }?.let { "sets ${it.joinToString(", ")}" },
            elsewhere.takeIf { it.isNotEmpty() }?.let { "runs ${it.joinToString(", ")}, not the `claude` on PATH" },
            "starts Claude Code through claude-code-router (`ccr code`)".takeIf { reading.router },
        ).joinToString("; ").ifEmpty { null }
        return Launcher(file, why == null, why, reading.configDir?.let(::expanded)?.let(::pathOrNull))
    }

    /** [file]'s text when it is a script of at most [LIST_MAX_BYTES] starting `#!`; null for anything else. */
    private fun scriptText(file: Path): String? = try {
        val head = Files.newInputStream(file).use { it.readNBytes(2) }
        val script = Files.size(file) <= LIST_MAX_BYTES && head.contentEquals(SHEBANG)
        if (script) Files.readString(file) else null
    } catch (_: IOException) {
        null
    }

    private fun listed(folder: Path): List<Path> = try {
        Files.newDirectoryStream(folder).use { stream -> stream.sortedBy { it.fileName.toString() } }
    } catch (_: IOException) {
        emptyList()
    }

    /** `~/` and `$HOME` spelled against the person's home, as a shell reads them. */
    private fun expanded(raw: String): String {
        val home = UserHome.dir(env).toString()
        return UserHome.expand(raw.replace("\${HOME}", home).replace("\$HOME", home), env)
    }

    private fun same(a: Path, b: Path): Boolean = real(a) == real(b)

    private fun real(path: Path): Path = try {
        path.toRealPath()
    } catch (_: IOException) {
        PathProbe.linkTarget(path) ?: path.toAbsolutePath().normalize()
    }

    private fun pathOrNull(raw: String): Path? = try {
        Paths.get(raw)
    } catch (_: InvalidPathException) {
        null
    }
}

private const val CLAUDE = "claude"

// why: a launcher is a short script; a file past this is a program, not one person's way of starting Claude Code
private const val LIST_MAX_BYTES = 64L * 1024
private val SHEBANG: ByteArray = "#!".toByteArray()
