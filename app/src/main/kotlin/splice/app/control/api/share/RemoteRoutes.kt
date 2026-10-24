// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). The console's Sharing page, the side of the machines
// this one uses:
//
//   POST   /api/remotes/scan            the sharing machines on this machine's networks, and what each offers
//   POST   /api/remotes                 {host, port?}: import one, as `splice remote add` does
//   POST   /api/remotes/{name}/sync     its commands' rows as it serves them now
//   DELETE /api/remotes/{name}          drop it, its commands and its token
//
// Each runs `splice remote`'s own classes, so the page and the CLI take one path. The last three change splice.toml's
// heads, which the daemon reads only at start, so each answers `restart: true` and the page takes the console's own
// restart (POST /api/daemon/restart), which waits for compactions and cuts only what the person agreed to. Each reads
// and writes the splice.toml the daemon booted (SharingParts), the one its links read.
package splice.app.control.api.share

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.cli.share.REMOTE_VERB
import splice.app.cli.share.RemoteAddress
import splice.app.cli.share.RemoteImport
import splice.app.cli.share.RemoteMachines
import splice.app.cli.share.ShareTopologyFile
import splice.core.topology.DEFAULT_SHARE_PORT
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.http.JsonBody
import splice.http.JsonReply
import splice.share.ShareHostName
import splice.share.remote.RemoteDiscovery
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteNames
import splice.share.remote.RemoteTrust
import splice.terminal.ConfirmPrompt
import splice.upstream.codemode.ProcessDispatchers

internal class RemoteRoutes(private val wired: SharingWired) {
    private val io = ProcessDispatchers().io()

    /** The daemon's environment, naming the splice.toml it booted; this process's where no daemon built the page's
     *  parts. */
    private val env: EnvReader get() = wired.parts()?.daemonEnv ?: EnvReader(System::getenv)

    /** The sharing machines that answer on this machine's networks, this one left out, each marked when joined. */
    suspend fun scan(): JsonReply = withContext(io) {
        val joined = ShareTopologyFile(Said().terminal, REMOTE_VERB, env).loaded()?.remotes?.keys.orEmpty()
        val self = ShareHostName().short()
        val found = RemoteDiscovery(RemoteHttp(RemoteTrust(null, "a sharing machine"))).scan(DEFAULT_SHARE_PORT)
            .filterNot { it.info.server == self }
        val machines = buildJsonObject {
            put("ok", true)
            putJsonArray("machines") { found.forEach { add(found(it, RemoteNames.of(it.info.server) in joined)) } }
        }
        JsonReply(HttpStatusCode.OK, machines.toString())
    }

    suspend fun import(call: ApplicationCall): JsonReply {
        val body = JsonBody().parse(call)
        val typed = JsonScalars.str(body, "host")?.trim()?.takeIf { it.isNotEmpty() } ?: return refused(IMPORT_BODY)
        // A found machine comes with its port; an address typed in the page is read as `splice remote add` reads it.
        val (host, port) = JsonScalars.int(body, "port")?.let { typed to it } ?: RemoteAddress.of(typed)
            ?: return refused("'$typed' is not an address: $ADDRESS_FORMS")
        return withContext(io) {
            val said = Said()
            val restart = DueRestart()
            val info = RemoteMachines(said.terminal, said.terminal, env, restart).info(host, port)
            if (info == null) {
                said.refused()
            } else {
                // A command already named here is added under `<command>-<machine>`, the CLI's default answer.
                val keep = ConfirmPrompt { _, default -> default }
                val imported = RemoteImport(said.terminal, said.terminal, env, keep, restart)
                    .import(RemoteFound(host, port, info))
                said.answer(imported, restart.due)
            }
        }
    }

    suspend fun sync(name: String): JsonReply =
        withContext(io) { RemoteVerb(env).let { it.answer(it.machines.sync(name)) } }

    suspend fun remove(name: String): JsonReply =
        withContext(io) { RemoteVerb(env).let { it.answer(it.machines.remove(name)) } }

    private fun refused(why: String): JsonReply =
        JsonReply(HttpStatusCode.BadRequest, buildJsonObject { put("error", why) }.toString())

    private fun found(machine: RemoteFound, joined: Boolean): JsonObject = buildJsonObject {
        put("server", machine.info.server)
        put("name", RemoteNames.of(machine.info.server))
        put("address", machine.address)
        put("port", machine.port)
        put("version", machine.info.version)
        machine.info.fingerprint?.let { put("fingerprint", it) }
        put("joined", joined)
        putJsonArray("commands") {
            machine.info.heads.forEach { head ->
                addJsonObject {
                    put("key", head.key)
                    put("command", head.command)
                    put("model", head.models.firstOrNull()?.label)
                }
            }
        }
    }
}

/** One `splice remote` verb run for the page: its lines kept, and its restart left to the page. */
private class RemoteVerb(env: EnvReader) {
    private val said = Said()
    private val restart = DueRestart()
    val machines = RemoteMachines(said.terminal, said.terminal, env, restart)

    fun answer(code: Int): JsonReply = said.answer(code == 0, restart.due)
}

// why: what a POST /api/remotes whose host is no address is told an address looks like.
private const val ADDRESS_FORMS = "give a name or address, and :port when it is not $DEFAULT_SHARE_PORT"

// why: what a POST /api/remotes that the route cannot read is told it must send.
private const val IMPORT_BODY = "the body must be {\"host\": \"<name or address>\", \"port\"?: <port>}"
