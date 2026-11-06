package splice.app.observability

import splice.core.util.EnvReader
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Only the known installed controller is discovered, using the daemon's launch PATH. No process is started. */
internal object PerfNativeProfiler {
    fun installed(env: EnvReader = EnvReader(System::getenv)): Path? = env("PATH").orEmpty().split(File.pathSeparator)
        .asSequence().filter { it.isNotBlank() }.mapNotNull { directory ->
            try {
                Path.of(directory).takeIf { it.isAbsolute }?.resolve("asprof")
                    ?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }?.toRealPath()
            } catch (_: InvalidPathException) {
                null
            } catch (_: java.io.IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }.firstOrNull()
}
