package splice.upstream

import java.nio.file.Path

/** Where a provider reaches and where it keeps state: the [baseUrl] each provider turns into its own
 *  [Provider.upstreamUrl], and V4-334's [stateDir], this head's own directory for what must outlive the daemon
 *  process (`heads/<key>/` under StatePaths.headsDir), named by the provider inside it. A null [stateDir] keeps
 *  nothing on disk: every unwired build and test. */
public data class ProviderLocations(val baseUrl: String, val stateDir: Path? = null)
