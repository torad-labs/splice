// NEW (Nov 13, 2026): the model each session named to fall back to while the one it asks for is held on every login,
// kept between daemon runs. On the beta channel the daemon restarts all day, and a setting that lived in memory alone
// would drop each time, sending the session back to waiting with no word.
package splice.app.head

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.util.DaemonLog
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.head.turn.SessionFallbacks
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Every session's fallback, in one document beside the other daemon state. */
internal const val SESSION_FALLBACKS_FILE: String = "session-fallbacks.json"

/** Each session's fallback model, by session id. A session id is Claude Code's own and names one session whatever
 *  head it runs on; each turn checks the model against its own head's catalog. */
@Serializable
private data class FallbacksDocument(val sessions: Map<String, String> = emptyMap())

/** Session fallbacks, read once at start and kept in memory for each turn's read. Every change is written whole and
 *  atomically before it takes effect, so a change the disk refused is not in force either. A document splice cannot
 *  read starts with none, said in the daemon log: a fallback is one command to set again. */
internal class SessionFallbackStore(private val file: Path) : SessionFallbacks {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val sessions = ConcurrentHashMap(read())

    override fun of(session: String): String? = sessions[session]

    /** Every session that has a fallback, and its model. */
    fun all(): Map<String, String> = sessions.toMap()

    /** Sets [session]'s fallback to [model], or clears it when [model] is null. Throws the disk's [IOException] with
     *  nothing changed. */
    @Synchronized
    fun set(session: String, model: String?) {
        val next = HashMap(sessions).apply { if (model == null) remove(session) else put(session, model) }
        SecureFile.writeAtomic0600(file, json.encodeToString(FallbacksDocument(next)))
        if (model == null) sessions.remove(session) else sessions[session] = model
    }

    private fun read(): Map<String, String> = try {
        json.decodeFromString<FallbacksDocument>(Files.readString(file)).sessions
    } catch (_: NoSuchFileException) {
        emptyMap()
    } catch (e: IOException) {
        unread(e)
    } catch (e: SerializationException) {
        unread(e)
    }

    private fun unread(e: Exception): Map<String, String> {
        val why = SafeFailureText.render(e)
        DaemonLog.write("[fallback] ${file.fileName} could not be read, so no session has a fallback: $why\n")
        return emptyMap()
    }
}
