// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on, df-credential and df-managed-launcher). The programs
// Claude Desktop runs for splice, each an owner-only (0700) script in the state folder, named by its absolute path:
//
//   <state dir>/desktop-credential   splice's configuration's `inferenceCredentialHelper`; runs `splice desktop
//                                    credential`, which prints the desktop key
//   <state dir>/desktop-launch       what a managed `processWrapper` can name; runs `splice desktop launch` with its
//                                    arguments, which starts the Code tab's Claude Code with the desktop key where
//                                    Desktop stripped it
//   <state dir>/desktop-mcp          what `claude_desktop_config.json` names for an MCP server reached at a URL
//                                    (desktop-3p-parity); runs `splice mcp stdio <name>`, the stdio bridge to it
//
// Desktop runs them with its own environment, which names no splice variable, so each bakes in, as absolute paths, the
// Java, the jar and the state folder of the splice that laid it. None holds a key: the verbs read it from the state
// folder each time. They are laid again at each `on` and each daemon start while splice's configuration is in
// Desktop's library, so they follow an upgrade.
package splice.app.desktop.setup

import splice.app.cli.AdminSupport
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal class DesktopPrograms(private val stateDir: Path) {
    /** Where the credential helper is laid. */
    val credential: Path = stateDir.resolve(CREDENTIAL_FILE).toAbsolutePath()

    /** Where the launcher is laid. */
    val launch: Path = stateDir.resolve(LAUNCH_FILE).toAbsolutePath()

    /** Where the MCP bridge is laid: the command `claude_desktop_config.json` names for a server reached at a URL. */
    val mcp: Path = stateDir.resolve(MCP_FILE).toAbsolutePath()

    /** Both programs laid for this process's Java and jar; the credential helper's absolute path, which splice's
     *  configuration names. */
    fun lay(): Path {
        val splice = spliceWords()
        val state = quoted(stateDir.toAbsolutePath().toString())
        SecureFile.writeAtomic(
            credential,
            "#!/bin/sh\n" +
                "# Laid by splice for Claude Desktop. It holds no key: it runs `splice desktop credential`,\n" +
                "# which reads the desktop key from splice's state folder.\n" +
                "SPLICE_STATE_DIR=$state exec $splice desktop credential\n",
            OWNER_ONLY_EXECUTABLE,
        )
        SecureFile.writeAtomic(
            launch,
            "#!/bin/sh\n" +
                "# Laid by splice for a managed processWrapper. It holds no key: it runs `splice desktop launch`,\n" +
                "# which gives a launch on splice's door the desktop key from splice's state folder.\n" +
                "exec $splice desktop launch --state-dir $state \"\$@\"\n",
            OWNER_ONLY_EXECUTABLE,
        )
        return credential
    }

    /** The MCP bridge laid for this process's Java and jar where it does not already read so; its absolute path. */
    fun layMcp(): Path {
        val state = quoted(stateDir.toAbsolutePath().toString())
        val text = "#!/bin/sh\n" +
            "# Laid by splice for Claude Desktop. It holds no key: it runs `splice mcp stdio <name>`,\n" +
            "# which reads the MCP access key from splice's state folder.\n" +
            "SPLICE_STATE_DIR=$state exec ${spliceWords()} mcp stdio \"\$@\"\n"
        val held = if (Files.isRegularFile(mcp, LinkOption.NOFOLLOW_LINKS)) Files.readString(mcp) else null
        if (held != text || !Files.isExecutable(mcp)) SecureFile.writeAtomic(mcp, text, OWNER_ONLY_EXECUTABLE)
        return mcp
    }

    /** Every program taken away; whether there was one. */
    fun remove(): Boolean = listOf(credential, launch, mcp).map(Files::deleteIfExists).any { it }

    /** This splice as shell words: its Java and jar, or its class path when it runs from a build. */
    private fun spliceWords(): String {
        val java = ProcessHandle.current().info().command().orElse(null)
            ?: Path.of(System.getProperty(JAVA_HOME), "bin", "java").toString()
        val run = AdminSupport.selfJar()?.let { listOf(java, "-jar", it.toAbsolutePath().toString()) }
            ?: listOf(java, "-cp", System.getProperty(CLASS_PATH).orEmpty(), MAIN_CLASS)
        return run.joinToString(" ", transform = ::quoted)
    }

    /** [word] as one shell word, in single quotes. */
    private fun quoted(word: String): String = "'" + word.replace("'", "'\\''") + "'"
}

// Read, written and run by its owner alone (0700).
private val OWNER_ONLY_EXECUTABLE = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)

// The programs' names in the state folder.
private const val CREDENTIAL_FILE = "desktop-credential"
private const val LAUNCH_FILE = "desktop-launch"
private const val MCP_FILE = "desktop-mcp"

private const val JAVA_HOME = "java.home"
private const val CLASS_PATH = "java.class.path"

// The class splice's jar names as its entry point, for a splice run from classes (a build, not an install).
private const val MAIN_CLASS = "splice.app.MainKt"
