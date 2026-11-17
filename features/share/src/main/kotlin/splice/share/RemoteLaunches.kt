// NEW: LAN share (docs/specs/lan-share.md). The launch of a command another machine
// serves (`[remote.<command>]`). Nothing here relays a turn or holds anything of the other machine's: the launch asks it
// for the head's models and timeout, with the address's `auth=` and `models=`, and builds the recipe a head here gets
// with the other machine's address in place of a loopback port. Claude Code then talks to that machine itself, holding
// a fixed word for its token, so a dead link is Claude Code's own "unable to connect", which is true. A machine that
// can't be reached, or refuses, is said before Claude Code starts, naming the machine and the reason.
//
// A server-mode server (docs/specs/server-mode.md, F2) answers only keys. On a laptop that signed in there with
// `splice login`, the launch asks with the key it holds, and Claude Code is given an apiKeyHelper that prints that
// key in place of the fixed word, so no key sits in the session's environment and a new sign-in reaches a session
// that is running. A server that refuses the ask for its key (signed out, or the key expired or was revoked) is
// launched from the last launch it answered, when there is one: Claude Code starts, and its helper or the server says
// to sign in.
package splice.share

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import splice.client.ClaudePolicy
import splice.core.topology.RemoteAddress
import splice.core.topology.RemoteAddressRead
import splice.core.topology.RemoteAddresses
import splice.core.topology.RemoteConfig
import splice.core.topology.SHARE_AUTH_PARAM
import splice.core.topology.SHARE_MODELS_PARAM
import splice.core.topology.SHARE_NODE_TOKEN
import splice.core.wire.HttpStatus
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.RemoteCommands
import splice.launch.RemoteLaunch
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

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
    io: CoroutineDispatcher,
    /** What `splice login` keeps of the servers this laptop signed in to (F2). */
    private val logins: ServerLogins? = null,
) : RemoteCommands {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = ShareHttp(io)

    override suspend fun launch(command: String): RemoteLaunch? {
        val remote = remotes[command] ?: return null
        return when (val read = RemoteAddresses.read(remote.url)) {
            is RemoteAddressRead.Read -> asked(command, read.address, remote.window)
            is RemoteAddressRead.Refused ->
                RemoteLaunch.Refused("$command's address in splice.toml is not one it can run on: ${read.why}")
        }
    }

    override fun ownTree(command: String): Path? = if (command in remotes) trees.of(command).own else null

    /** [command]'s launch as [address]'s server answers it, on the [window] this machine pinned for it if it did, or
     *  why it can't be had. */
    private suspend fun asked(command: String, address: RemoteAddress, window: Long?): RemoteLaunch {
        val where = "$command runs on ${http.hostOf(address.server)} (${address.server})"
        val answer = when (val asked = http.get(launchUri(address), logins?.of(address.server))) {
            is ShareAnswer.Unreachable -> return RemoteLaunch.Refused("$where, which can't be reached: ${asked.why}")
            is ShareAnswer.Answered -> asked
        }
        val launch = launchOf(command, address, answer)
        return when {
            launch != null -> RemoteLaunch.Ready(spec(command, address, launch, window))
            answer.status != HttpStatus.OK -> {
                val why = http.said(answer.body) + signIn(address, answer.status)
                RemoteLaunch.Refused("$where, which refused it: $why")
            }
            else -> RemoteLaunch.Refused("$where, which answered with something that is not a launch")
        }
    }

    /** The launch [command] starts with: the one the server [answer]ed, kept on a server this laptop signs in to;
     *  or, when that server refused it for want of a key, the one it last answered, so Claude Code still starts and
     *  its apiKeyHelper says how to sign in. */
    private fun launchOf(command: String, address: RemoteAddress, answer: ShareAnswer.Answered): SharedLaunch? {
        val shared = answer.takeIf { it.status == HttpStatus.OK }
            ?.let { http.decoded(it.body, SharedLaunch.serializer()) }
        val logins = logins ?: return shared
        if (shared != null) {
            logins.keepLaunch(address.server, command, json.encodeToJsonElement(SharedLaunch.serializer(), shared))
        }
        return shared ?: logins.launch(address.server, command)
            ?.takeIf { answer.status == HttpStatus.UNAUTHORIZED }
            ?.let(::decoded)
    }

    /** What a refusal for want of a key ([status] 401) adds on a server this laptop signs in to: how to sign in. */
    private fun signIn(address: RemoteAddress, status: Int): String =
        logins?.helper(address.server)?.takeIf { status == HttpStatus.UNAUTHORIZED }
            ?.let { "; sign in with: splice login ${address.server}" }.orEmpty()

    private fun decoded(kept: JsonElement): SharedLaunch? = try {
        json.decodeFromJsonElement(SharedLaunch.serializer(), kept)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The recipe a head here gets, pointed at [address]: the server's models, timeout and statusline, this machine's
     *  own tree for [command], a token that opens nothing (or, on a server this laptop signed in to, the apiKeyHelper
     *  that prints its key), and a /login that says where the logins live. A head whose
     *  client picks its own models there leaves them to the person here too. The context window is the server's for
     *  the model it pins, or the [window] this machine pinned in its place (F7). */
    private fun spec(command: String, address: RemoteAddress, shared: SharedLaunch, window: Long?): LaunchSpec {
        val signIn = LaunchSignIn(
            loginCommand = shared.signIn.command,
            signInLabel = "${shared.signIn.label} on ${shared.machine}",
            signsInOn = shared.machine,
            headKey = command,
        )
        val statusline = "${address.turns}/statusline"
        val helper = logins?.helper(address.server)
        val gateway = LaunchGateway(
            port = 0,
            bearer = helper?.let { GatewayBearer.Helper(it) } ?: GatewayBearer.Planted(SHARE_NODE_TOKEN),
            apiTimeoutMs = shared.timeoutMs,
            statuslineCommand = "curl -sS --connect-timeout $STATUSLINE_CONNECT_S --data-binary @- '$statusline'",
            remote = address.turns,
            remoteClaude = shared.clientModels,
        )
        val served = shared.models.launchModels()
        val models = window?.let { served.copy(contextWindow = it) } ?: served
        return LaunchSpec(trees.of(command), models, signIn, gateway, policy)
    }

    private fun launchUri(address: RemoteAddress): URI {
        val params = listOfNotNull(
            address.auth?.let { "$SHARE_AUTH_PARAM=${encoded(it)}" },
            address.models.takeIf { it.isNotEmpty() }?.let { "$SHARE_MODELS_PARAM=${encoded(it.joinToString(","))}" },
        )
        val query = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
        return URI.create("${address.server}/${address.head}/launch$query")
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

// why: the statusline redraws often; a server that is off should cost it two seconds, not curl's default minutes.
private const val STATUSLINE_CONNECT_S = 2
