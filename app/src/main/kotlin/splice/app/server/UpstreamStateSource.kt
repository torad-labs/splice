package splice.app.server

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.provider.local.JdkLocalHttp
import splice.app.provider.local.LocalHttpBodyTooLarge
import splice.core.process.ProcessTree
import splice.core.topology.ServerState
import splice.core.util.MonoClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_OK
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** A state read has no caller credentials; neither stderr nor malformed source text enters diagnostics. */
internal class UpstreamStateSource(private val config: ServerState, private val configDir: Path) {
    private val http = lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(STATE_TIMEOUT_S)).build() }

    suspend fun read(): String {
        val file = config.file
        val url = config.url
        return when {
            file != null -> runInterruptible { file(configDir.resolve(file)) }
            url != null -> url(url.trim())
            else -> runInterruptible { command(checkNotNull(config.command)) }
        }
    }

    fun diagnosis(failure: Throwable): String = when {
        failure is StateReadFailure -> failure.reason
        failure is LocalHttpBodyTooLarge -> OVERSIZED_STATE_OUTPUT
        failure is HttpTimeoutException -> HTTP_STATE_TIMEOUT
        config.file != null -> "could not read state file"
        config.url != null -> "state HTTP request failed"
        else -> "could not run state command or read its output"
    }

    fun close() {
        if (http.isInitialized()) http.value.shutdownNow()
    }

    private fun file(path: Path): String {
        if (!Files.isRegularFile(path)) throw StateReadFailure("state file is missing or not a regular file")
        return text(path)
    }

    private suspend fun url(url: String): String {
        val response = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(STATE_TIMEOUT_S)) {
            runInterruptible { JdkLocalHttp(client = http.value).getBounded(url, MAX_STATE_BYTES) }
        } ?: throw StateReadFailure(HTTP_STATE_TIMEOUT)
        if (response.status !in HTTP_OK until HTTP_MULT_CHOICE) {
            throw StateReadFailure("state URL returned HTTP ${response.status}")
        }
        return response.body
    }

    private fun command(argv: List<String>): String {
        val process = ProcessBuilder(argv).directory(configDir.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment().clear() }
            .start()
        return try {
            process.outputStream.close()
            process.inputStream.use { commandOutput(process, it) }
        } finally {
            if (process.isAlive) ProcessTree(process).kill()
        }
    }

    private fun commandOutput(process: Process, stdout: InputStream): String {
        val deadline = MonoClock.nowMs() + TimeUnit.SECONDS.toMillis(STATE_TIMEOUT_S)
        val bytes = ByteArrayOutputStream()
        do {
            drain(stdout, bytes)
            val remaining = deadline - MonoClock.nowMs()
            if (remaining <= 0) throw StateReadFailure("state command timed out")
        } while (!process.waitFor(minOf(remaining, STDOUT_DRAIN_MS), TimeUnit.MILLISECONDS))
        val exit = process.exitValue()
        if (exit != 0) throw StateReadFailure("state command exited $exit")
        drain(stdout, bytes)
        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    // Read only buffered bytes: waiting for EOF could hang on a descriptor inherited by a child.
    private fun drain(stdout: InputStream, bytes: ByteArrayOutputStream) {
        val available = stdout.available()
        if (available > MAX_STATE_BYTES - bytes.size()) throw StateReadFailure(OVERSIZED_STATE_OUTPUT)
        if (available > 0) bytes.write(stdout.readNBytes(available))
    }

    private fun text(path: Path): String = Files.newInputStream(path).use {
        val bytes = it.readNBytes(MAX_STATE_BYTES + 1)
        if (bytes.size > MAX_STATE_BYTES) throw StateReadFailure(OVERSIZED_STATE_OUTPUT)
        bytes.decodeToString(throwOnInvalidSequence = true)
    }
}

private class StateReadFailure(val reason: String) : IOException(reason)

// why: the source says only a state; a stalled check must not hold up the next sample or daemon shutdown.
private const val STATE_TIMEOUT_S = 5L

// why: drain stdout during the timed wait to let a child write more than the pipe can hold.
private const val STDOUT_DRAIN_MS = 25L

// why: bound every state source while leaving room for an operator's JSON message.
private const val MAX_STATE_BYTES = 16_384

private const val OVERSIZED_STATE_OUTPUT = "state output is too large"
private const val HTTP_STATE_TIMEOUT = "state HTTP request timed out"
