// NEW: desktop-full (docs/specs/desktop-full.md, df-app-on, df-credential and df-managed-launcher). The programs
// Claude Desktop runs for splice, each an owner-only (0700) script in the state folder, named by its absolute path:
//
//   <state dir>/desktop-credential   splice's configuration's `inferenceCredentialHelper`; runs `splice desktop
//                                    credential`, which prints the desktop key
//   <state dir>/desktop-launch       what a managed processWrapper can name; runs the optional Desktop companion
//                                    or the original Claude Code, restoring the key only for this splice's door
//   <state dir>/desktop-mcp          what `claude_desktop_config.json` names for an MCP server reached at a URL
//                                    (desktop-3p-parity); runs `splice mcp stdio <name>`, the stdio bridge to it
//
// Desktop runs them with its own environment. Absolute paths follow this installation, and key bytes never enter
// the files. Each `on` and daemon restart refreshes them while Desktop is on the gateway.
package splice.app.desktop.setup

import splice.core.config.InstallPaths
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.SecureFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal class DesktopPrograms(
    private val stateDir: Path,
    env: EnvReader = EnvReader(System::getenv),
    private val pipe: Path? = InstallPaths(envReader = env).desktopPipe,
) {
    private val paths = StatePaths(baseOverride = stateDir.toAbsolutePath(), envReader = env)
    private val hooks = DesktopHooks(paths)

    /** Where the credential helper is laid. */
    val credential: Path = stateDir.resolve(CREDENTIAL_FILE).toAbsolutePath()

    /** Where the launcher is laid. */
    val launch: Path = stateDir.resolve(LAUNCH_FILE).toAbsolutePath()

    /** Where the MCP bridge is laid: the command `claude_desktop_config.json` names for a server reached at a URL. */
    val mcp: Path = stateDir.resolve(MCP_FILE).toAbsolutePath()

    /** Both programs laid for this process's Java and jar; the credential helper's absolute path, which splice's
     *  configuration names. */
    fun lay(door: String? = null): Path {
        val port = door?.let { URI(it).port } ?: Knob.CONTROL_PORT.count().toInt()
        hooks.lay(port)
        val splice = spliceWords()
        val state = DesktopShell.word(stateDir.toAbsolutePath().toString())
        SecureFile.writeAtomic(
            credential,
            "#!/bin/sh\n" +
                "# Laid by splice for Claude Desktop. It holds no key: it runs `splice desktop credential`,\n" +
                "# which reads the desktop key from splice's state folder.\n" +
                "SPLICE_STATE_DIR=$state exec $splice desktop credential\n",
            ownerOnlyExecutable,
        )
        SecureFile.writeAtomic(
            launch,
            DesktopShell.script(
                "desktop-launch.sh",
                "/bin/sh",
                mapOf(
                    "desktop_key" to paths.desktopKeyFile.toString(),
                    "port" to port.toString(),
                    "pipe" to pipe?.toString().orEmpty(),
                    "harness_map" to paths.desktopHarnessesFile.toString(),
                    "hooks" to hooks.file.toString(),
                    "SPLICE_STATE_DIR" to paths.stateDir.toString(),
                ),
            ),
            ownerOnlyExecutable,
        )
        return credential
    }

    /** The MCP bridge laid for this process's Java and jar where it does not already read so; its absolute path. */
    fun layMcp(): Path {
        val state = DesktopShell.word(stateDir.toAbsolutePath().toString())
        val text = "#!/bin/sh\n" +
            "# Laid by splice for Claude Desktop. It holds no key: it runs `splice mcp stdio <name>`,\n" +
            "# which reads the MCP access key from splice's state folder.\n" +
            "SPLICE_STATE_DIR=$state exec ${spliceWords()} mcp stdio \"\$@\"\n"
        val held = if (Files.isRegularFile(mcp, LinkOption.NOFOLLOW_LINKS)) Files.readString(mcp) else null
        val mode = if (held == null) null else Files.getPosixFilePermissions(mcp, LinkOption.NOFOLLOW_LINKS)
        if (held != text || mode != ownerOnlyExecutable) SecureFile.writeAtomic(mcp, text, ownerOnlyExecutable)
        return mcp
    }

    /** Every program taken away; whether there was one. */
    fun remove(): Boolean =
        listOf(credential, launch, mcp, hooks.file, hooks.foreground).map(Files::deleteIfExists).any { it }

    /** This splice as shell words (SpliceSelf). */
    private fun spliceWords(): String = SpliceSelf().command().joinToString(" ", transform = DesktopShell::word)
}

// Read, written and run by its owner alone (0700).
internal val ownerOnlyExecutable = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)

// The programs' names in the state folder.
private const val CREDENTIAL_FILE = "desktop-credential"
private const val LAUNCH_FILE = "desktop-launch"
private const val MCP_FILE = "desktop-mcp"
