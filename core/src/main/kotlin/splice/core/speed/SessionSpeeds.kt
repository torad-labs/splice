// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 3) — the speed each Claude Code SESSION runs at, by its session id,
// as Codex keeps a speed per thread. /fast and /ultrafast set it (the head's UserPromptSubmit hook calls the daemon),
// and every request of the session reads it as the turn is built, a subagent's and a compaction included.
//
// It also keeps the model each session's last turn ran on, because a tier the session's model does not offer is
// refused, and the session's model is that one (or the head's pinned model before a first turn).
//
// PERSISTED: the speed is the person's choice, and a daemon restart must not quietly put a fast session back at
// standard, or a standard one at a price it never chose. Only a CHANGE touches the disk: a speed changes when the person
// types a command, and a session's model about never, so this is a handful of writes per session, not one per turn.
package splice.core.speed

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.LruSizing
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: sessions come and go for the daemon's whole life; a person works in tens at once, never thousands, and the
// least recently touched one is the one forgotten first.
private const val CAPACITY = 2048

// why: the keys of each session's entry in the saved file.
private const val SAVED_SPEED = "speed"
private const val SAVED_MODEL = "model"
private const val SAVED_PROVIDER = "provider"

/** Session id -> its speed, last turn's model, and last routed provider. Bounded, LRU evicted first. */
public class SessionSpeeds(
    /** Where the settings survive a daemon restart; null keeps them in memory only. */
    private val store: Path? = null,
    private val log: LogSink = LogSink {},
) {
    private data class Entry(
        val speed: Speed = Speed.STANDARD,
        val model: String? = null,
        val provider: String? = null,
    )

    private val lock = Any()
    private val ioLock = Any()
    private val sessions =
        object : LinkedHashMap<String, Entry>(LruSizing.INITIAL_CAPACITY, LruSizing.LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > CAPACITY
        }

    init {
        store?.let(::load)
    }

    /** The speed [sessionId] runs at: standard for a session that never set one, and for no session at all. */
    public fun speedOf(sessionId: String?): Speed =
        sessionId?.takeIf(String::isNotBlank)?.let { synchronized(lock) { sessions[it] }?.speed } ?: Speed.STANDARD

    /** Set [sessionId]'s speed, saved before this returns, so a restart right after keeps it. */
    public fun set(sessionId: String, speed: Speed) {
        update(sessionId, speed = speed)
    }

    /** The model [sessionId]'s last turn ran on, or null before its first turn here. */
    public fun lastModel(sessionId: String?): String? =
        sessionId?.takeIf(String::isNotBlank)?.let { synchronized(lock) { sessions[it] }?.model }

    /** The provider key [sessionId]'s latest Door turn routed to, or null when unrecorded. */
    public fun lastProvider(sessionId: String?): String? =
        sessionId?.takeIf(String::isNotBlank)?.let { synchronized(lock) { sessions[it] }?.provider }

    /** [sessionId]'s turn runs on [model] (and optionally Door-routed [provider]). */
    public fun ran(sessionId: String?, model: String, provider: String? = null) {
        val id = sessionId?.takeIf(String::isNotBlank) ?: return
        if (model.isNotBlank()) update(id, model = model, provider = provider?.takeIf(String::isNotBlank))
    }

    private fun update(sessionId: String, speed: Speed? = null, model: String? = null, provider: String? = null) {
        val changed = synchronized(lock) {
            val before = sessions[sessionId] ?: Entry()
            val after = Entry(speed ?: before.speed, model ?: before.model, provider ?: before.provider)
            sessions[sessionId] = after
            after != before
        }
        if (changed) store?.let(::persist)
    }

    private fun load(path: Path) {
        if (!Files.exists(path)) return
        try {
            val entries = Json.parseToJsonElement(Files.readString(path)).jsonObject
            synchronized(lock) {
                entries.forEach { (id, value) ->
                    (value as? JsonObject)?.let { o ->
                        val speed = SpeedWords.named(JsonScalars.str(o, SAVED_SPEED)) ?: Speed.STANDARD
                        sessions[id] = Entry(
                            speed,
                            JsonScalars.str(o, SAVED_MODEL),
                            JsonScalars.str(o, SAVED_PROVIDER),
                        )
                    }
                }
            }
        } catch (e: IOException) {
            log(unreadable(path, SafeFailureText.render(e)))
        } catch (_: SerializationException) {
            log(unreadable(path, "it is not valid JSON"))
        } catch (e: IllegalArgumentException) {
            // A well-formed JSON document of the wrong shape (an array, a bare value).
            log(unreadable(path, SafeFailureText.render(e)))
        }
    }

    // Least-recently-touched first, the map's own iteration order, so a reload keeps the eviction order.
    private fun persist(path: Path) = synchronized(ioLock) {
        val snapshot = synchronized(lock) { sessions.toMap() }
        val json = JsonWire.string(
            buildJsonObject {
                snapshot.forEach { (id, entry) ->
                    putJsonObject(id) {
                        put(SAVED_SPEED, entry.speed.word)
                        entry.model?.let { put(SAVED_MODEL, it) }
                        entry.provider?.let { put(SAVED_PROVIDER, it) }
                    }
                }
            },
        )
        try {
            SecureFile.writeAtomic0600(path, json)
        } catch (e: IOException) {
            log(
                "[speed] session speeds not saved to $path: ${SafeFailureText.render(e)}; " +
                    "a restart forgets the last change\n",
            )
        }
    }

    private fun unreadable(path: Path, reason: String): String =
        "[speed] session speeds at $path ignored: $reason; every session starts at standard speed\n"
}
