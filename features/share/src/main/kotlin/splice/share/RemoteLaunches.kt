// NEW: LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24). The launch of a command another machine
// serves (`[remote.<command>]`). Nothing here relays a turn or holds anything of the other machine's: the launch asks it
// for the head's models and timeout, with the address's `auth=` and `models=`, and builds the recipe a head here gets
// with the other machine's address in place of a loopback port. Claude Code then talks to that machine itself, holding
// a fixed word for its token, so a dead link is Claude Code's own "unable to connect", which is true. A machine that
// can't be reached, or refuses, is said before Claude Code starts, naming the machine and the reason.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.ClaudePolicy
import splice.client.login.SignInWay
import splice.core.topology.RemoteAddress
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.core.topology.RemoteConfig
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.RemoteCommands
import splice.launch.RemoteLaunch
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** The Claude Code config tree a command keeps on this machine: its own `~/.claude-<command>`, and the trees a
 *  `-r ID` resume may find a session in, as for a head here. */
public fun interface RemoteTrees {
    public fun of(command: String): HeadTrees
}

public class RemoteLaunches(
    /** `[remote.*]` as splice.toml holds it: each command and its address. */
    private val remotes: Map<String, RemoteConfig>,
    private val trees: RemoteTrees,
    private val policy: ClaudePolicy,
    /** The blocking ask runs here. */
    private val io: CoroutineDispatcher,
) : RemoteCommands {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(REMOTE_CONNECT_MS))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun launch(command: String): RemoteLaunch? {
        val remote = remotes[command] ?: return null
        return when (val read = RemoteAddresses.read(remote.url)) {
            is RemoteAddressRead.Read -> asked(command, read.address)
            is RemoteAddressRead.Refused ->
                RemoteLaunch.Refused("$command's address in splice.toml is not one it can run on: ${read.why}")
        }
    }

    /** [command]'s launch as [address]'s server answers it, or why it can't be had. */
    private suspend fun asked(command: String, address: RemoteAddress): RemoteLaunch {
        val where = "$command runs on ${hostOf(address)} (${address.server})"
        val request = HttpRequest.newBuilder(launchUri(address)).timeout(Duration.ofMillis(REMOTE_ANSWER_MS)).GET()
        val sent = withContext(io) {
            Cancellables.runCatchingCancellable { http.send(request.build(), HttpResponse.BodyHandlers.ofString()) }
        }
        val response = sent.getOrElse { failure ->
            return RemoteLaunch.Refused("$where, which can't be reached: ${SafeFailureText.render(failure)}")
        }
        if (response.statusCode() != HttpStatus.OK) {
            return RemoteLaunch.Refused("$where, which refused it: ${said(response.body())}")
        }
        return Cancellables.runCatchingCancellable { json.decodeFromString(SharedLaunch.serializer(), response.body()) }
            .fold(
                onSuccess = { shared -> RemoteLaunch.Ready(spec(command, address, shared)) },
                onFailure = { RemoteLaunch.Refused("$where, which answered with something that is not a launch") },
            )
    }

    /** The recipe a head here gets, pointed at [address]: the server's models, timeout and statusline, this machine's
     *  own tree for [command], a token that opens nothing, and a /login that says where the logins live. */
    private fun spec(command: String, address: RemoteAddress, shared: SharedLaunch): LaunchSpec {
        val signIn = LaunchSignIn(
            loginCommand = shared.signIn.command,
            signInLabel = "${shared.signIn.label} on ${shared.machine}",
            signInWay = SignInWay.Elsewhere(shared.machine),
            headKey = command,
        )
        val statusline = "${address.server}/${address.head}/statusline"
        val gateway = LaunchGateway(
            port = 0,
            inferenceToken = NODE_TOKEN,
            apiTimeoutMs = shared.timeoutMs,
            statuslineCommand = "curl -sS --connect-timeout $STATUSLINE_CONNECT_S --data-binary @- '$statusline'",
            remote = address.turns,
        )
        return LaunchSpec(trees.of(command), shared.models.launchModels(), signIn, gateway, policy)
    }

    private fun launchUri(address: RemoteAddress): URI {
        val params = listOfNotNull(
            address.auth?.let { "$AUTH_PARAM=${encoded(it)}" },
            address.models.takeIf { it.isNotEmpty() }?.let { "$MODELS_PARAM=${encoded(it.joinToString(","))}" },
        )
        val query = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
        return URI.create("${address.server}/${address.head}/launch$query")
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** The server's host as the address names it: `torad-server.lan`, `192.168.1.236`. */
    private fun hostOf(address: RemoteAddress): String =
        URI.create(address.server).host?.removeSurrounding("[", "]") ?: address.server

    /** The message of the server's error body, or a short note when it sent none. */
    private fun said(body: String): String {
        val error = JsonScalars.objectOrNull(json, body)?.get("error") as? JsonObject
        return JsonScalars.str(error, "message")?.removePrefix(ERROR_PREFIX) ?: "it gave no reason"
    }
}

// The word a node's Claude Code is given as its auth token. It opens nothing: the server signs every turn with its
// own logins, and its listener drops whatever a request carries.
private const val NODE_TOKEN = "splice-lan"

// What ShareErrors puts before every message, which the node's own words already say.
private const val ERROR_PREFIX = "splice share: "

// why: a machine on the same LAN accepts in milliseconds; three seconds covers a sleeping Wi-Fi radio waking, and a
// launch to a machine that is off is said that soon, before Claude Code starts.
private const val REMOTE_CONNECT_MS = 3_000L

// why: the server builds the launch from memory; ten seconds covers a busy machine and still says a hung one.
private const val REMOTE_ANSWER_MS = 10_000L

// why: the statusline redraws often; a server that is off should cost it two seconds, not curl's default minutes.
private const val STATUSLINE_CONNECT_S = 2
