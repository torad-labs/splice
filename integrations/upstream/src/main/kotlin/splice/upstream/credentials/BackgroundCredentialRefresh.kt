// NEW: prefetch has no awaiting request to report an unexpected refresh failure, so it owns a log boundary.
package splice.upstream.credentials

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splice.core.auth.Credentials
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText

/** Runs a credential prefetch on its lifecycle owner's scope, reporting nonfatal failures once per launch. */
public class BackgroundCredentialRefresh(
    private val scope: CoroutineScope,
    private val logTag: String,
    private val log: LogSink,
) {
    /** The current token is already served. Cancellation and fatal errors still propagate unchanged. */
    public fun launch(refresh: CredentialRefresh<Credentials?>): Job = scope.launch {
        Cancellables.runCatchingBestEffort { refresh() }.fold(
            onSuccess = {},
            onFailure = { failure ->
                log("[$logTag] background refresh failed: " + SafeFailureText.render(failure))
            },
        )
    }
}
