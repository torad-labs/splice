// NEW: V4-183 (2026-09-20) — which sessions THIS head started or resumed, kept beside the head's
// own config as <configDir>/splice-sessions.json. The shared transcript tree (ProjectsLink, V4-64 /
// V4-168) is what lets any head join any session by name; it is also why a bare `-c` went wrong:
// Claude Code's --continue picks the NEWEST transcript in the cwd's directory whoever wrote it, and
// appends this head's turns INTO that file. Measured 2026-09-20: nine transcripts in seven days
// carried two model families, seven of them deepseek sessions continued in place by a claude head
// within one hour. The operator's ruling: keep the shared tree, bound continue to the head.
//
// A transcript says nothing about which head wrote it (the model id is rewritten on resume, and two
// heads can serve the same family), so ownership is recorded where it is decided: the daemon learns
// every session start through the SessionStart hook each head carries (ResumeHook, sources startup
// and resume) and writes {id, cwd, transcript, at} here. A launch with -c reads the newest entry for
// its cwd and resumes THAT id by name (HeadBoundedContinue). The index is per head and lives in the
// head's own dir, so no key, no shared state and no reading of other heads' files; the vanilla client
// has no hook, so its sessions are never entered and never continued by a head unless chosen by name.
//
// The file is a convenience, never a gate: absent or unparseable it reads as empty (one log line),
// a record that cannot be written is logged and the session runs, and a stale entry whose transcript
// is gone is skipped on read. Bounded to the newest MAX_OWNED_SESSIONS so a head's file never grows without
// limit; cwd paths are canonicalised (realpath) on both sides, since the shim sends $PWD and the hook
// sends Claude Code's cwd, which can spell one directory two ways through a symlink.
package splice.client.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock

/** The index file's name inside a head's config dir — one declaration, read by the tests too. */
internal const val SESSION_OWNERSHIP_FILE: String = "splice-sessions.json"

// why: a head starts a handful of sessions a day, so 500 entries is months of history while the
// file, read on every launch, stays at a few tens of kilobytes.
private const val MAX_OWNED_SESSIONS = 500
private const val OWNED_FIELD_SESSIONS = "sessions"
private const val OWNED_FIELD_ID = "id"
private const val OWNED_FIELD_CWD = "cwd"
private const val OWNED_FIELD_TRANSCRIPT = "transcript"
private const val OWNED_FIELD_AT = "at"

/** The id shape an entry may carry — a path component and an argv word, never anything else. */
private val OWNED_ID_SHAPE = Regex("[A-Za-z0-9_-]{1,128}")

public data class OwnedSession(val id: String, val cwd: String, val transcript: String, val at: Long)

public class SessionOwnership(
    private val configDir: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: Path get() = configDir.resolve(SESSION_OWNERSHIP_FILE)

    /** Enter [id] as a session this head owns in [cwd]; a later record of the same id replaces the
     *  earlier one (the head resumed it again). An id outside the session-id shape is refused and
     *  said, never written. */
    public fun record(id: String, cwd: String, transcript: Path) {
        if (!OWNED_ID_SHAPE.matches(id)) {
            log("[sessions] $configDir: a hook named a session id that is not a session id; not recorded\n")
            return
        }
        val entry = OwnedSession(id, canonical(cwd), transcript.toString(), clock.millis())
        // Newest first, and the entry just recorded first among equals: the sort is stable, so two
        // records in one millisecond (a test, a burst of launches) still resolve to the latest.
        val kept = listOf(entry) + load().filter { it.id != id }
        save(kept.sortedByDescending(OwnedSession::at).take(MAX_OWNED_SESSIONS))
    }

    /** The newest session this head owns in [cwd] whose transcript still exists, or null. */
    public fun newestFor(cwd: String): OwnedSession? {
        val wanted = canonical(cwd)
        return load()
            .filter { it.cwd == wanted }
            .sortedByDescending(OwnedSession::at)
            .firstOrNull { Files.isRegularFile(Path.of(it.transcript)) }
    }

    private fun load(): List<OwnedSession> {
        if (!Files.isRegularFile(file)) return emptyList()
        val parsed = Cancellables.runCatchingCancellable {
            json.parseToJsonElement(Files.readString(file)).jsonObject
        }
        // A damaged index is a lost convenience, not a broken launch: say so once and start over at
        // the next record, which overwrites it.
        val root = parsed.getOrElse { cause ->
            log("[sessions] $file is unreadable (${SafeFailureText.render(cause)}) — treated as empty\n")
            null
        }
        val sessions = root?.get(OWNED_FIELD_SESSIONS) as? JsonArray
        return sessions?.mapNotNull { element -> entry(element as? JsonObject) }.orEmpty()
    }

    private fun entry(obj: JsonObject?): OwnedSession? {
        val id = JsonScalars.str(obj, OWNED_FIELD_ID)?.takeIf(OWNED_ID_SHAPE::matches) ?: return null
        val at = JsonScalars.str(obj, OWNED_FIELD_AT)?.toLongOrNull() ?: return null
        val cwd = JsonScalars.str(obj, OWNED_FIELD_CWD)
        val transcript = JsonScalars.str(obj, OWNED_FIELD_TRANSCRIPT)
        return if (cwd != null && transcript != null) OwnedSession(id, cwd, transcript, at) else null
    }

    private fun save(entries: List<OwnedSession>) {
        val body = buildJsonObject {
            put(
                OWNED_FIELD_SESSIONS,
                buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put(OWNED_FIELD_ID, entry.id)
                                put(OWNED_FIELD_CWD, entry.cwd)
                                put(OWNED_FIELD_TRANSCRIPT, entry.transcript)
                                put(OWNED_FIELD_AT, entry.at)
                            },
                        )
                    }
                },
            )
        }
        val written = Cancellables.runCatchingCancellable {
            Files.createDirectories(configDir)
            val staged = Files.createTempFile(configDir, ".splice-sessions", ".tmp")
            Files.writeString(staged, body.toString())
            Files.move(staged, file, REPLACE_EXISTING, ATOMIC_MOVE)
        }
        written.exceptionOrNull()?.let { cause ->
            val why = SafeFailureText.render(cause)
            log("[sessions] $file could not be written ($why) — this session is not recorded\n")
        }
    }

    /** realpath when the directory resolves, the given spelling otherwise (a cwd that no longer
     *  exists still names the same place the transcript was written under). */
    private fun canonical(cwd: String): String =
        Cancellables.runCatchingCancellable { Path.of(cwd).toRealPath().toString() }.getOrElse { cwd }
}
