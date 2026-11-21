// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on and df-credential). The executable Claude Desktop runs as
// splice's configuration's `inferenceCredentialHelper`: `<state dir>/desktop-credential`, owner-only (0700), named by
// its absolute path, which runs `splice desktop credential`. Desktop runs it with its own environment, which names no
// splice variable, so it bakes in, as absolute paths, the Java, the jar and the state folder of the splice that laid
// it. It holds no key: the verb reads it from the state folder each time. It is laid again at each `on` and each daemon
// start while splice's configuration is in Desktop's library, so it follows an upgrade.
package splice.app.desktop

import splice.app.cli.AdminSupport
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal class DesktopCredentialHelper(private val stateDir: Path) {
    /** Where the helper is laid. */
    val file: Path = stateDir.resolve(HELPER_FILE).toAbsolutePath()

    /** The helper laid for this process's Java and jar; its absolute path. */
    fun lay(): Path {
        val java = ProcessHandle.current().info().command().orElse(null)
            ?: Path.of(System.getProperty(JAVA_HOME), "bin", "java").toString()
        val run = AdminSupport.selfJar()?.let { listOf(java, "-jar", it.toAbsolutePath().toString()) }
            ?: listOf(java, "-cp", System.getProperty(CLASS_PATH).orEmpty(), MAIN_CLASS)
        val words = (run + listOf("desktop", "credential")).joinToString(" ", transform = ::quoted)
        val script = "#!/bin/sh\n" +
            "# Laid by splice for Claude Desktop. It holds no key: it runs `splice desktop credential`,\n" +
            "# which reads the desktop key from splice's state folder.\n" +
            "SPLICE_STATE_DIR=${quoted(stateDir.toAbsolutePath().toString())} exec $words\n"
        SecureFile.writeAtomic(file, script, OWNER_ONLY_EXECUTABLE)
        return file
    }

    /** The helper taken away; whether there was one. */
    fun remove(): Boolean = Files.deleteIfExists(file)

    /** [word] as one shell word, in single quotes. */
    private fun quoted(word: String): String = "'" + word.replace("'", "'\\''") + "'"
}

// Read, written and run by its owner alone (0700).
private val OWNER_ONLY_EXECUTABLE = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)

// The helper's name in the state folder.
private const val HELPER_FILE = "desktop-credential"

private const val JAVA_HOME = "java.home"
private const val CLASS_PATH = "java.class.path"

// The class splice's jar names as its entry point, for a splice run from classes (a build, not an install).
private const val MAIN_CLASS = "splice.app.MainKt"
