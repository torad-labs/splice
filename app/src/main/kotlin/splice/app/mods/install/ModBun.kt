package splice.app.mods.install

import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal const val BUN_NOT_FOUND = "Bun not found"

/** CLI records its Bun at install time; the daemon can also discover one when starting a mod later. */
internal class ModBun(private val env: EnvReader = EnvReader(System::getenv)) {
    fun atInstall(): Path? = path(env("SPLICE_BUN"))?.toAbsolutePath()?.normalize() ?: onPath()

    fun forRun(recorded: String?): Path? = executable(path(recorded)) ?: executable(path(env("SPLICE_BUN")))
        ?: onPath() ?: executable(UserHome.dir(env).resolve(".bun/bin/bun"))

    fun missing(name: String): String =
        "Bun was not found (checked the install record, SPLICE_BUN, PATH and ~/.bun/bin/bun). " +
            "Install Bun from https://bun.sh, then run splice mod enable $name. " +
            "To point Splice to Bun explicitly: SPLICE_BUN=/path/to/bun splice mod install <folder-or-tarball>."

    private fun onPath(): Path? = env("PATH").orEmpty().splitToSequence(File.pathSeparatorChar)
        .filter(String::isNotEmpty)
        .mapNotNull { executable(path(it)?.resolve("bun")) }
        .firstOrNull()

    private fun executable(path: Path?): Path? = path?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?.toAbsolutePath()?.normalize()

    private fun path(raw: String?): Path? = try {
        raw?.takeIf(String::isNotBlank)?.let(Path::of)
    } catch (_: InvalidPathException) {
        null
    }
}
