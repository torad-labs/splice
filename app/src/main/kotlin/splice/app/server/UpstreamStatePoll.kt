package splice.app.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import splice.app.probe.MS_PER_SECOND
import splice.core.server.UpstreamAvailability
import splice.core.server.UpstreamSignalParser
import splice.core.server.UpstreamStateReading
import splice.core.topology.ServerState
import splice.core.util.Cancellables
import splice.upstream.LifecycleScope
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import java.nio.file.Path
import java.time.Clock

/** One signal per server, sampled off the turn path and published as a single reading. */
internal class UpstreamStatePoll(
    private val config: ServerState,
    configDir: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val ticker: Ticker = ProcessTicker(),
    dispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
) : UpstreamAvailability {
    private val source = UpstreamStateSource(config, configDir)
    private val scope = LifecycleScope(dispatcher)

    @Volatile
    private var current = UpstreamStateReading()

    override fun reading(): UpstreamStateReading = current

    fun open() {
        scope.launch {
            do {
                val output = Cancellables.runCatchingBestEffort { source.read() }
                current = output.fold(
                    onSuccess = ::parsed,
                    onFailure = { failed(source.diagnosis(it)) },
                )
            } while (isActive && ticker.awaitTick(config.everySeconds * MS_PER_SECOND))
        }
    }

    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        source.close()
    }

    private fun parsed(text: String): UpstreamStateReading {
        val signal = UpstreamSignalParser.parse(text) ?: return failed("unrecognized state output")
        return UpstreamStateReading(signal, lastChecked = clock.instant())
    }

    private fun failed(why: String): UpstreamStateReading =
        current.copy(lastError = why, lastChecked = clock.instant())
}
