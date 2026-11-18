// NEW: the harness (docs/specs/harness.md). A head's harness, built from what the daemon handed the head: the
// capabilities it declares, where its threads and the model's kept calls are recorded, and the log and clock the head
// runs by.
package splice.harness

import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.port.TurnHarness
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.net.http.HttpClient
import java.time.Duration

public class HeadHarness {
    /** The harness of head [head] over [inputs], logging to [log] and timing its holds by [clock]. */
    public fun of(head: String, inputs: HarnessInputs, log: LogSink, clock: ElapsedClock): TurnHarness {
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(CONNECT_S))
            .build()
        val io = ProcessDispatchers().io()
        val calls = CapabilityCall(HttpWorker(client, io, inputs.keys))
        val records = ThreadRecords(inputs.recordsDir, log)
        val stops = AgentStops()
        val answers = AgentAnswers(head, calls, stops, log, clock)
        val kept = AgentRecords(inputs.recordsDir?.resolve(KEPT_DIR), log)
        // The worker of a call made beside the client's outlives the turn that called it: the harness owns where it
        // runs.
        val besides = AgentBeside(inputs.capabilities, kept, answers, LifecycleScope(io))
        val agent = AgentCalls(head, inputs.capabilities, kept, answers, besides, stops)
        return Harness(head, inputs.capabilities, records, calls, log, clock, agent)
    }
}

/** why: a worker on this machine or the next answers a connection in well under this; one that cannot be reached is a
 *  failure the turn should not wait its whole time limit for. */
private const val CONNECT_S = 10L

/** why: the threads' records are named by their thread, a main thread's by its session; the kept calls are named by
 *  their session too, so they sit in a folder of their own. */
private const val KEPT_DIR = "agent-calls"
