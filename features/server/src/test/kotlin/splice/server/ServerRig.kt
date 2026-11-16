// What the tests of server mode's listener stand on (docs/specs/server-mode.md): a head standing in for one the daemon
// runs, and server mode opened the way the daemon opens it, on loopback, on a port of the test's own. Every test that
// needs the listener up opens it here and mints its keys here, so there is one stand-in head and one way to serve.
package splice.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertInstanceOf
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.testing.TestPorts
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.launch.LaunchSpec
import splice.share.SharedHead
import splice.share.SharedHeads
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import java.nio.file.Path
import java.time.Clock

/** A head this machine offers, standing in for one the daemon runs: it is up at [port] (down while [up] is false),
 *  has nothing to launch and holds no login. */
internal class StandInHead(
    override val port: Int,
    override val key: String = "team",
    override val command: String = "claude-team",
    private val up: Boolean = true,
) : SharedHead {
    override fun running(): Boolean = up

    override fun launch(): LaunchSpec? = null

    override fun logins(): List<String> = emptyList()

    override fun pin(login: String, session: String): Boolean = false

    override fun maxRequestBytes(): Int = 1_000_000

    override fun requestReadTimeoutMs(): Long = 5_000
}

/** Server mode on loopback for one test: it writes its lines to [log], reads the time off [clock], the servers
 *  behind its providers are what [upstreams] says, and a kept-alive turn's silences are waited out on [waiter]. */
internal class ServerRig(
    private val log: LogSink = LogSink { },
    private val clock: Clock = Clock.systemUTC(),
    private val upstreams: HeadUpstreams = object : HeadUpstreams {},
    private val waiter: Waiter = ProcessWaiter(),
) {
    /** The port the listener answers on. */
    val port: Int = TestPorts.reserve()

    /** `[server]` as the plainest splice.toml has it: listening on [port] and serving `claude-team`. A test copies it
     *  with what it sets. */
    val config: ServerConfig = ServerConfig(listen = "127.0.0.1:$port", commands = listOf("claude-team"))

    private var mode: ServerMode? = null

    /** Opens [server] as the daemon does, over the machine `gpu-box` and the [heads] it offers. [state] holds the
     *  daemon's state and is splice.toml's folder too. A rig that was closed opens again on the same port.
     *  [signinSecret] is the client secret the daemon would read from `[server.signin] client_secret_env`. */
    fun open(
        state: Path,
        heads: List<SharedHead>,
        server: ServerConfig = config,
        signinSecret: String? = null,
    ): ServerMode {
        val machine = object : SharedHeads {
            override val machine: String = "gpu-box"

            override fun offered(): List<SharedHead> = heads
        }
        val reach = ServerReach(TurnKey(MgmtKey(StatePaths(baseOverride = state))), TestPorts.reserve())
        val opened = ServerMode(server, state.resolve("server"), Dispatchers.IO, log, clock, upstreams, waiter)
        mode = opened
        opened.open(ServerAddress("127.0.0.1", port), machine, reach, state, signinSecret)
        return opened
    }

    /** A key for [person], minted as the operator's CLI mints it, that expires at [expires] when one is given. */
    fun keyFor(person: String, expires: String? = null): String {
        val asked = buildJsonObject {
            put("person", person)
            expires?.let { put("expires", it) }
        }
        val answer = runBlocking { checkNotNull(mode) { "the server is not open" }.keyApi.create(asked) }
        return assertInstanceOf(ServerAnswer.Done::class.java, answer).body.getValue("key").jsonPrimitive.content
    }

    /** Closes the server, as the daemon does at stop. */
    fun close() {
        runBlocking { mode?.close() }
    }
}
