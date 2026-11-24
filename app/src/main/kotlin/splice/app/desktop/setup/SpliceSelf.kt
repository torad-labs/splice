// NEW: desktop-antigravity. This splice as a command another program can run: the Java running it and its jar, or
// its class path where it runs from a build. The programs laid for Claude Desktop bake it in (DesktopPrograms), and
// `splice desktop launch` starts `splice desktop acp` and runs itself again with it.
package splice.app.desktop.setup

import splice.app.cli.AdminSupport
import java.nio.file.Path

internal class SpliceSelf {
    /** This splice's command words, its own arguments to follow. */
    fun command(): List<String> {
        val java = ProcessHandle.current().info().command().orElse(null)
            ?: Path.of(System.getProperty(JAVA_HOME), "bin", "java").toString()
        return AdminSupport.selfJar()?.let { listOf(java, "-jar", it.toAbsolutePath().toString()) }
            ?: listOf(java, "-cp", System.getProperty(CLASS_PATH).orEmpty(), MAIN_CLASS)
    }
}

private const val JAVA_HOME = "java.home"
private const val CLASS_PATH = "java.class.path"

// The class splice's jar names as its entry point, for a splice run from classes (a build, not an install).
private const val MAIN_CLASS = "splice.app.MainKt"
