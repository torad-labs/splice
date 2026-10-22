package splice.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.util.LogSink
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
        val process = try {
            withContext(NonCancellable + ioDispatcher) {
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
            return null
        } catch (_: SecurityException) {
            return null
        }

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
}

internal fun interface NativeCodexMcpBlockingOperation<T> {
    operator fun invoke(): T
}

internal class NativeCodexMcpRpcFailure(val reason: String) : Exception(reason)

internal fun interface NativeCodexMcpDialog<T> {
    suspend operator fun invoke(): T
}
