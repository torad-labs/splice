// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). What GET /api/share answers, the Sharing page's one
// read: this machine's sharing (the table splice.toml holds and the listener as it is now), the machines that joined it
// with when each was last heard from, and the machines this one joined with whether each answers. Built from the file
// as it is now, so a change the page just made reads back at once, and from the daemon's memory for the rest.
package splice.app.control.api.share

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.control.SharingParts
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.share.ShareCertificate
import splice.share.ShareHostName
import splice.share.ShareState
import splice.share.remote.RemoteReach
import splice.share.remote.RemoteTopology
import splice.share.remote.RemotesStore

internal class SharingView(private val parts: SharingParts, private val file: Topology) {
    private val state = ShareState(StatePaths(envReader = parts.env))

    private val store = RemotesStore(state.remotesFile)

    /** The machines a token is kept for; a store that cannot be read reads as none, and each then says so. */
    private val tokens: Set<String> by lazy {
        Cancellables.runCatchingCancellable { store.names().toSet() }
            .fold(onSuccess = { it }, onFailure = { emptySet() })
    }

    /** The machines this one joined whose Sessions switch is on here; none when the store cannot be read. */
    private val crossing: Set<String> by lazy {
        Cancellables.runCatchingCancellable { tokens.filter(store::sessions).toSet() }
            .fold(onSuccess = { it }, onFailure = { emptySet() })
    }

    fun json(): JsonObject = buildJsonObject {
        put("machine", ShareHostName().short())
        put("sharing", sharing())
        putJsonArray("joined") { joined().forEach(::add) }
        putJsonArray("remotes") { file.remotes.keys.forEach { add(remote(it)) } }
    }

    /** `[share]` as the file says it, the listener as it is, and every command with whether it is offered. */
    private fun sharing(): JsonObject {
        val share = file.share
        val listening = parts.listener.state()
        val offered = share.sharedHeads(file).toSet()
        return buildJsonObject {
            put("enabled", share.enabled)
            put("port", share.port)
            put("listening", listening.listening)
            // False while the listener has not yet followed the file: the moment between a write and its reopening.
            put("following", listening.share == share)
            listening.why?.let { put("why", it) }
            put("fingerprint", listening.fingerprint ?: ShareCertificate(state.certificateFile).fingerprint())
            putJsonArray("commands") {
                file.heads.forEach { (key, head) ->
                    addJsonObject {
                        put("key", key)
                        put("command", head.claude.command ?: key)
                        put("model", head.pinnedModel)
                        put("offered", key in offered)
                        put("serving", key in listening.offered)
                        share.unshareable(file, key)?.let { put("why", it) }
                    }
                }
            }
        }
    }

    /** The machines that joined, with when each was last heard from as the daemon holds it now. */
    private fun joined(): List<JsonObject> {
        val activity = parts.listener.clients.activity
        val seen = activity.now().associateBy { it.name }
        val today = activity.today()
        return parts.listener.clients.all().map { machine ->
            buildJsonObject {
                put("name", machine.name)
                put("address", machine.address)
                put("joined_at", machine.joinedAt)
                put("sessions", machine.sessions)
                seen[machine.name]?.let {
                    put("last_seen", it.lastSeen)
                    put("turns_today", it.turnsOn(today))
                }
                crossing(machine.name, this)
            }
        }
    }

    /** How sessions cross with [machine]: how many of its sessions are listed here, and how many of this machine's it
     *  was last shown, left out until it has been. Nothing where this daemon hosts no mirrors. */
    private fun crossing(machine: String, into: JsonObjectBuilder) {
        val counts = parts.listener.mirrors?.counts(machine) ?: return
        into.put("sessions_here", counts.here)
        counts.there?.let { into.put("sessions_there", it) }
    }

    /** One machine this one joined: where it is, its pin, whether a token is kept for it, whether it answered the last
     *  check, and the commands relayed to it here. A machine the daemon has not checked yet (one imported since it
     *  started) carries no `reachable`. */
    private fun remote(name: String): JsonObject {
        val remote = file.remotes.getValue(name)
        val reach: RemoteReach? = parts.remotes?.reach()?.get(name)
        return buildJsonObject {
            put("name", name)
            put("url", remote.url)
            remote.fingerprint?.let { put("fingerprint", it) }
            put("token", name in tokens)
            put("sessions", name in crossing)
            crossing(name, this)
            reach?.let { found ->
                put("reachable", found.reachable)
                put("since", found.since)
                found.why?.let { put("why", it) }
            }
            putJsonArray("commands") {
                RemoteTopology(file).heads(name).forEach { (key, head) ->
                    val there = file.providers[head.provider]?.baseUrl?.substringAfterLast('/')
                    addJsonObject {
                        put("key", key)
                        put("command", head.claude.command ?: key)
                        put("model", head.pinnedModel)
                        put("down", there != null && reach?.down?.contains(there) == true)
                    }
                }
            }
        }
    }
}
