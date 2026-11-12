package splice.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException
import java.nio.file.Path

/** Opens only a transport child owned by Splice; closing it never stops the shared native daemon. */
internal class NativeCodexMcpRpc(private val log: LogSink) {
    private val ioDispatcher = ProcessDispatchers().io()

    internal data class LaunchContext(
        val command: List<String>,
        val cwd: Path? = null,
        val environment: Map<String, String> = emptyMap(),
    )

    suspend fun connect(
        home: Path,
        launch: LaunchContext,
        initializeTimeoutMs: Long,
    ): NativeCodexMcpRpcSession? {
        val process = start(home, launch) ?: return null

        val session = NativeCodexMcpRpcSession(process, ioDispatcher, log)
        return try {
            session.initialize(initializeTimeoutMs)
            session
        } catch (cancelled: CancellationException) {
            session.close()
            throw cancelled
        } catch (_: NativeCodexMcpRpcFailure) {
            session.close()
            null
        } catch (_: IOException) {
            session.close()
            null
        }
    }

    private suspend fun start(home: Path, launch: LaunchContext): Process? =
        try {
            withContext(NonCancellable + ioDispatcher) {
                // Codex rejects an explicit home before initialize when a fresh account has no directory yet.
                // Prepare it owner-only before the native process can write config or credentials into it.
                if (SecureFile.ownerOnlyDirectory(home) != null) return@withContext null
                ProcessBuilder(launch.command)
                    .apply {
                        launch.cwd?.let { directory(it.toFile()) }
                        environment().putAll(launch.environment)
                        environment()["CODEX_HOME"] = home.toString()
                        redirectError(ProcessBuilder.Redirect.DISCARD)
                    }
                    .start()
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}

internal fun interface NativeCodexMcpBlockingOperation<T> {
    operator fun invoke(): T
}

internal class NativeCodexMcpRpcFailure(val reason: String) : Exception(reason)

internal fun interface NativeCodexMcpDialog<T> {
    suspend operator fun invoke(): T
}
