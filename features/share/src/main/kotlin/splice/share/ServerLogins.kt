// NEW: server mode on a laptop (docs/specs/server-mode.md, F2). What `splice login <server>` keeps of each server it
// signed in to, under `<state>/server-logins`: a record (the server, the name this laptop signs in as there, who signed
// in, the key's prefix and expiry, and the last launch each of its commands answered) and, apart, the key itself in an
// owner-only file holding nothing else. Claude Code never holds the key in its environment: a command on that server
// launches with an apiKeyHelper that prints the key file, so a new sign-in reaches a running session the next time
// Claude Code asks, which it does after a 401. `splice logout <server>` removes the key file and keeps the rest, so
// signing in again names the same device and the server keeps that device's keys apart from the laptop's others.
// The record also keeps what the server's profile offered at the last `splice remote add` or `refresh`: its commands
// (F7) and its telemetry relay (F8), which a command's telemetry export reaches with an otelHeadersHelper that prints
// the same key file.
package splice.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.SecureFile
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.SecureRandom

/** What a laptop keeps of one server it signed in to. Never the key: that is in its own file. */
@Serializable
internal data class ServerLoginRecord(
    val server: String,
    val device: String,
    val person: String? = null,
    val prefix: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    /** The launch each command on the server last answered, by command, so a launch the server refuses for want of
     *  a key still starts Claude Code, whose helper then says how to sign in. */
    val launches: Map<String, JsonElement> = emptyMap(),
    /** The commands the server's profile listed when this laptop last linked them (F7), so a refresh links only the
     *  ones it starts offering after that, and unlinks only the ones it drops; null before the first. */
    val offered: List<String>? = null,
    /** Where the server relays Claude Code's own telemetry, as its profile named it at the last add or refresh (F8);
     *  null while it relays none. */
    val telemetry: String? = null,
)

/** A sign-in the server answered: the [person] it names and the [key] it minted, shown by [prefix] everywhere. */
public class ServerSignedIn(
    public val person: String,
    private val key: String,
    public val prefix: String,
    public val expiresAt: String?,
) {
    public fun reveal(): String = key

    override fun toString(): String = "ServerSignedIn(person=$person, prefix=$prefix, key=<redacted>)"
}

/** The logins of the splice whose state [env] names, kept under `<state>/server-logins`: where every command of a laptop
 *  reads them, so `splice login`, `splice logout` and `splice remote` agree on the folder. */
public class LaptopLogins(env: EnvReader) {
    public val logins: ServerLogins = ServerLogins(StatePaths(envReader = env).serverLoginsDir)
}

public class ServerLogins(
    /** `<state>/server-logins` (StatePaths.serverLoginsDir). */
    private val dir: Path,
    private val random: SecureRandom = SecureRandom(),
) {
    private val letters = ('a'..'z') + ('0'..'9')

    /** [server] as one origin, `scheme://host:port`, however it was written; null when it is no http(s) server. */
    public fun origin(server: String): String? {
        val uri = try {
            URI(server.trim().trimEnd('/'))
        } catch (_: URISyntaxException) {
            null
        }
        val scheme = uri?.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
        val host = uri?.host?.lowercase()
        if (scheme == null || host == null) return null
        val port = uri.port.takeIf { it > 0 } ?: uri.toURL().defaultPort
        return "$scheme://${if (':' in host && !host.startsWith('[')) "[$host]" else host}:$port"
    }

    /** The key this laptop holds for [server], or null when it holds none; the share module's asks send it as a
     *  bearer. */
    public fun of(server: String): String? {
        val file = files(server)?.key ?: return null
        return try {
            Files.readString(file).trim().takeIf(String::isNotEmpty)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    /** The name this laptop signs in to [server] as: the one it used before, or a new one from [machine]'s name and
     *  a few letters, so two laptops with one host name stay two devices. */
    public fun device(server: String, machine: String): String {
        record(server)?.device?.let { return it }
        val name = machine.substringBefore('.').lowercase()
            .filter { it.isLetterOrDigit() || it == '-' }
            .take(DEVICE_HOST_MAX)
        val suffix = buildString { repeat(SUFFIX_LENGTH) { append(letters[random.nextInt(letters.size)]) } }
        return if (name.isEmpty()) suffix else "$name-$suffix"
    }

    /** Keeps [signedIn], the key in its own file first, so the helper hands it out from the next time it is asked. */
    public fun signedIn(server: String, device: String, signedIn: ServerSignedIn) {
        val files = requireNotNull(files(server)) { "not a server address" }
        SecureFile.writeAtomic0600(files.key, signedIn.reveal() + "\n")
        val kept = files.record.read()
        files.record.write(
            ServerLoginRecord(
                server = requireNotNull(origin(server)),
                device = device,
                person = signedIn.person,
                prefix = signedIn.prefix,
                expiresAt = signedIn.expiresAt,
                launches = kept?.launches.orEmpty(),
                offered = kept?.offered,
                telemetry = kept?.telemetry,
            ),
        )
    }

    /** Who is signed in to [server] on this laptop, or null when nobody is. */
    public fun person(server: String): String? = record(server)?.person

    /** Forgets the key this laptop holds for [server]; false when it held none. */
    public fun forget(server: String): Boolean {
        val files = files(server) ?: return false
        val held = Files.deleteIfExists(files.key)
        val kept = files.record.read()
        if (kept?.person != null) files.record.write(kept.copy(person = null, prefix = null, expiresAt = null))
        return held || kept?.person != null
    }

    /** The helpers a session of a command on [server] reads this laptop's key there through, or null when this laptop
     *  never signed in there (a LAN share). */
    public fun helpers(server: String): KeyHelpers? {
        val files = files(server)?.takeIf { Files.exists(it.record.path) } ?: return null
        return KeyHelpers(requireNotNull(origin(server)), files.key)
    }

    /** The launch [command] on [server] last answered, or null. */
    internal fun launch(server: String, command: String): JsonElement? =
        record(server)?.launches?.get(command)

    /** Keeps [launch] as what [command] on [server] answers, when this laptop signed in there and it changed. */
    internal fun keepLaunch(server: String, command: String, launch: JsonElement) {
        val files = files(server) ?: return
        val kept = files.record.read() ?: return
        if (kept.launches[command] == launch) return
        files.record.write(kept.copy(launches = kept.launches + (command to launch)))
    }

    /** The commands [server]'s profile listed when this laptop last linked them, or null when it never linked from
     *  one there (F7). */
    public fun offered(server: String): List<String>? = record(server)?.offered

    /** Keeps what [server]'s profile offers now, on a server this laptop holds a login for: the [commands] it lists,
     *  and the [telemetry] relay it names, or null when it names none (F8). */
    public fun keepOffered(server: String, commands: List<String>, telemetry: String?) {
        val files = files(server) ?: return
        val kept = files.record.read() ?: return
        val now = kept.copy(offered = commands, telemetry = telemetry)
        if (now != kept) files.record.write(now)
    }

    /** Where [server] relays Claude Code's own telemetry, as its profile named it when this laptop last linked from it;
     *  null when it relays none (F8). */
    public fun telemetry(server: String): String? = record(server)?.telemetry

    private fun record(server: String): ServerLoginRecord? = files(server)?.record?.read()

    private fun files(server: String): LoginFiles? {
        val origin = origin(server) ?: return null
        val name = origin.replace("://", "-").replace(Regex("[^A-Za-z0-9.-]"), "_")
        val record = StateFile(dir.resolve("$name.json"), ServerLoginRecord.serializer())
        return LoginFiles(record, dir.resolve("$name.key"))
    }

    private data class LoginFiles(val record: StateFile<ServerLoginRecord>, val key: Path)
}

// why: a host's short name is a few words; 24 characters keep a device name readable in a report's device column.
private const val DEVICE_HOST_MAX = 24

// why: six letters of 36 tell apart the laptops one team gives one host name to, by far.
private const val SUFFIX_LENGTH = 6
