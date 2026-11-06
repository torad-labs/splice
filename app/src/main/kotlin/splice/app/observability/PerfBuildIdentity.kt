package splice.app.observability

import splice.app.ApplicationArtifactStamp
import splice.core.GATEWAY_VERSION
import splice.core.perf.observation.PerfIdentity
import java.util.UUID

/** The loaded class carries its build inputs' fingerprint; replacing an install path cannot relabel this process. */
internal object PerfBuildIdentity {
    private val build = "compiled-artifact-inputs-sha256:${ApplicationArtifactStamp.fingerprint()}"

    fun create(revision: String): PerfIdentity = PerfIdentity(
        UUID.randomUUID().toString(),
        build,
        GATEWAY_VERSION,
        revision,
    )
}
