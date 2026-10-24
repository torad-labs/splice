// NEW: LAYOUT-01 — the two verbs app composes, `splice add` over AddCommand and `splice add-model`
// over AddModelVerb. Each keeps its test seams (the checks' network, the roster editor) internal; app
// supplies only the terminal and the ports in AddSeams.
package splice.configuration.add

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.terminal.MultiSelectPrompt
import splice.terminal.SelectPrompt
import java.net.http.HttpClient
import java.nio.file.Path
import javax.net.ssl.SSLContext

/** `splice add <profile>`. [output] takes every line the verb prints; [errors] only what resolving the
 *  control port says about a splice.toml that does not load. [tls] is the trust the checks reach the endpoint with:
 *  `splice remote` passes the sharing machine's pinned certificate, so the base url check proves the name the head
 *  will use answers with that one. Null keeps the platform's. */
public class AddVerb(
    output: TerminalOutput,
    errors: TerminalOutput,
    ports: AddPorts,
    localRows: LocalRuntimeRows = LocalRuntimeRows { _, _ -> LocalRowsAnswer() },
    tls: SSLContext? = null,
) {
    private val http = tls?.let { HttpClient.newBuilder().sslContext(it).build() } ?: HttpClient.newHttpClient()
    private val command = AddCommand(output, errors, AddChecks(output, JdkAddHttp(http), localRows), ports)
    private val runtime = RuntimeHeadAdd(output, command)
    private val remote = RemoteHeadAdd(command)

    /** True when the head was saved and is reachable as printed; false leaves splice.toml unchanged
     *  or names the restart that is still owed. */
    public suspend fun add(args: List<String>, env: EnvReader): Boolean = command.add(args, env)

    /** `splice setup`'s local-model step: the head a runtime described, through the same checks, save
     *  and restart as [add], with the placeholder key its unauthenticated endpoint needs. Same answer. */
    public suspend fun addRuntime(head: RuntimeHead, env: EnvReader): Boolean = runtime.add(head, env)

    /** `splice remote`: a head another of the person's machines shares, regenerated here (RemoteHeadAdd). */
    public suspend fun addRemote(head: RemoteHead, env: EnvReader): Boolean = remote.add(head, env)
}

/** `splice add-model`: OpenRouter catalog rows picked through [select] and [multi]. */
public class AddModelsVerb(select: SelectPrompt, multi: MultiSelectPrompt) {
    private val verb = AddModelVerb(select, multi)

    /** Whether the picked rows were written to the splice.toml at [path]; a refused edit answers
     *  [AddModelsResult.Refused] with the whole explanation. */
    public fun add(path: Path): AddModelsResult = verb.add(path)
}
