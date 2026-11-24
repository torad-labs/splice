package splice.server.ledger

import kotlinx.coroutines.runInterruptible
import splice.core.process.ProcessTree
import splice.core.topology.metering.ServerCostMeter
import java.io.IOException
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_OK
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Only affirmative signals can advance a meter. A probe never uses credentials from a request. */
internal class MeterSignals(private val configDir: Path) {
    private val http = lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(METER_PROBE_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER).build()
    }

    fun close() {
        if (http.isInitialized()) http.value.shutdownNow()
    }

    suspend fun running(meter: ServerCostMeter): Boolean = try {
        when {
            meter.file != null -> runInterruptible { activeFile(configDir.resolve(checkNotNull(meter.file))) }
            meter.url != null -> runInterruptible { activeUrl(checkNotNull(meter.url)) }
            else -> runInterruptible { activeCommand(checkNotNull(meter.argv)) }
        }
    } catch (_: IOException) {
        false
    }

    private fun activeFile(path: Path): Boolean = Files.isRegularFile(path) &&
        Files.newInputStream(path).use { it.readNBytes(MAX_SIGNAL_BYTES + 1) }.let {
            it.size <= MAX_SIGNAL_BYTES && it.decodeToString().trim() == "true"
        }

    private fun activeUrl(url: String): Boolean {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(METER_PROBE_SECONDS)).build()
        val response = http.value.send(request, HttpResponse.BodyHandlers.ofInputStream())
        return response.body().use { response.statusCode() in ACTIVE_STATUS }
    }

    private fun activeCommand(argv: List<String>): Boolean {
        val process = ProcessBuilder(argv).directory(configDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment().clear() }
            .start()
        try {
            process.outputStream.close()
            return process.waitFor(METER_PROBE_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
        } finally {
            if (process.isAlive) ProcessTree(process).kill()
        }
    }
}

internal const val METER_PROBE_SECONDS = 5L
private const val MAX_SIGNAL_BYTES = 64
private val ACTIVE_STATUS = HTTP_OK until HTTP_MULT_CHOICE
