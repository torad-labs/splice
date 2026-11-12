package splice.codemode

import kotlinx.coroutines.CoroutineDispatcher
import splice.codemode.host.PoolLimits
import splice.core.util.ElapsedClock
import splice.upstream.Ticker
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.codemode.ProcessTicker

/** Real legacy worker fixture for gateway cancellation, ordering and packaged-language regressions. */
public class LegacyCodeModeRuntime(
    limits: PoolLimits = PoolLimits(),
    advanceTimeoutMs: Long = DEFAULT_ADVANCE_TIMEOUT_MS,
    launch: LegacyHostLaunch = LegacyHostLaunch(),
    ioDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    now: ElapsedClock = ProcessElapsedNow(),
    ticker: Ticker = ProcessTicker(),
) : CodeModeRuntime by JvmCodeModeRuntime(limits, advanceTimeoutMs, launch.options(), ioDispatcher, now, ticker)
