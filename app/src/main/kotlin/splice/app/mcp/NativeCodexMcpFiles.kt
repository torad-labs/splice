package splice.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import splice.core.config.StatePaths
import splice.core.topology.TopologyFileTarget
import splice.core.util.FileTightening
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.upstream.codemode.ProcessDispatchers
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

private const val CODEX_FILES_CLEANUP_REASON = "Codex app-server child cleanup was incomplete."

internal class NativeCodexMcpFiles(paths: StatePaths) {
    private val statePaths = paths
    private val ioDispatcher = ProcessDispatchers().io()

    suspend fun configTarget(configFile: Path): Path =
        try {
            withContext(ioDispatcher) {
                TopologyFileTarget.of(configFile).toAbsolutePath().normalize()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            throw NativeCodexMcpConfigFailure("Could not read the Codex user configuration layer.")
        } catch (_: SecurityException) {
            throw NativeCodexMcpConfigFailure("Could not read the Codex user configuration layer.")
        } catch (_: InvalidPathException) {
            throw NativeCodexMcpConfigFailure("Could not read the Codex user configuration layer.")
        }

    suspend fun prepareWrite(target: Path, secure: Boolean): Boolean =
        withContext(ioDispatcher) {
            backupIfPresent(target) && ensureSecureBeforeWrite(target, secure)
        }

    suspend fun tighten(target: Path): Boolean = withContext(ioDispatcher) { tightenOnIo(target) }

    private fun backupIfPresent(target: Path): Boolean =
        try {
            !Files.exists(target) || createBackup(target)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun createBackup(target: Path): Boolean {
        val bytes = Files.readAllBytes(target)
        val content = String(bytes, StandardCharsets.UTF_8)
        if (!content.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) return false

        val backupDirectory = statePaths.stateDir.resolve("native-codex-config-backups")
        if (SecureFile.ownerOnlyDirectory(backupDirectory) != null) return false
        val backupName = "config-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".toml.bak"
        SecureFile.writeAtomic0600(backupDirectory.resolve(backupName), content)
        return true
    }

    private fun ensureSecureBeforeWrite(target: Path, secure: Boolean): Boolean =
        when {
            !secure -> true
            Files.exists(target) -> tightenOnIo(target)
            else -> createSecureConfig(target)
        }

    private fun createSecureConfig(target: Path): Boolean =
        try {
            SecureFile.writeAtomic0600(target, "")
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun tightenOnIo(target: Path): Boolean =
        try {
            SecureFile.ownerOnlyFile(target) !is FileTightening.Open
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
}

internal class NativeCodexMcpOwnedProcess(private val log: LogSink) {
    fun terminate(process: Process): Boolean =
        try {
            process.destroyForcibly()
            true
        } catch (_: SecurityException) {
            report("terminate")
            false
        }

    fun close(process: Process): Boolean {
        var complete = terminate(process)
        if (!closeStream("stdout", process.inputStream)) complete = false
        if (!closeStream("stdin", process.outputStream)) complete = false
        if (!closeStream("stderr", process.errorStream)) complete = false
        return complete
    }

    private fun closeStream(name: String, stream: Closeable): Boolean =
        try {
            stream.close()
            true
        } catch (_: IOException) {
            report(name)
            false
        } catch (_: SecurityException) {
            report(name)
            false
        }

    private fun report(operation: String) {
        log("$CODEX_FILES_CLEANUP_REASON $operation failed.")
    }
}
