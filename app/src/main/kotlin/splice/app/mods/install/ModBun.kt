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

    fun forRun(recorded: String?): Path? = executable(path(recorded)) ?: onPath()
        ?: executable(UserHome.dir(env).resolve(".bun/bin/bun"))

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
