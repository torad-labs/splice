// NEW: the harness (docs/specs/harness.md). A head's harness, built from what the daemon handed the head.
package splice.head.harness

import splice.head.HeadDeps
import splice.upstream.codemode.ProcessDispatchers
import java.net.http.HttpClient
import java.time.Duration

internal class HeadHarness {
    /** The harness of head [head], or null where the daemon handed it none. */
    fun of(head: String, deps: HeadDeps): Harness? {
        val inputs = deps.seams.session.harness ?: return null
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(CONNECT_S))
            .build()
        val worker = HttpWorker(client, ProcessDispatchers().io(), inputs.keys)
        return Harness(
            head,
            inputs.capabilities,
            ThreadRecords(inputs.recordsDir, deps.log),
            CapabilityCall(worker, deps.seams.session.sessionProject),
            deps.log,
            deps.seams.clock,
        )
    }
}

/** why: a worker on this machine or the next answers a connection in well under this; one that cannot be reached is a
 *  failure the turn should not wait its whole time limit for. */
private const val CONNECT_S = 10L
