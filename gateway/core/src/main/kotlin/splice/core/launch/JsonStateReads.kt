// NEW: the two strictness modes for reading .claude* JSON state, moved out of
// ClaudeConfigMaterializer.kt (concentration, 2026-09-05) as a pure relocation: the class keeps its
// name, members and argument lists; only its visibility widened from file-private to internal so
// the materializer can still construct it. Behaviour is unchanged.
package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

// The two strictness modes for reading .claude* JSON state (DR-11c; a collaborator so the
// materializer stays under the type-size wall). TOLERANT is for merge SOURCES that are never
// rewritten (global settings, global .claude.json) — degrading those to empty loses an inherit,
// not operator state. STRICT is for the file a rewrite is about to REPLACE (the
// KeyStore.entriesStrict doctrine): ABSENT = a fresh head, safe to seed; UNPARSEABLE = unknown
// state — abort the materialize (and with it the launch) rather than rebuild a five-key file over
// every local key Claude Code owns, the approved customApiKeyResponses included.
internal class JsonStateReads(private val json: Json, private val log: LogSink) {

    // Sweep 2026-08-31 (absence class): both modes carried an exists/symlink pre-gate. Tolerant
    // read an unreadable global as silent EMPTY (the head lost every carried key with no trace)
    // and skipped readable dotfiles symlinks; strict read a symlinked local as "fresh head" and
    // seeded OVER the operator's link. Now the read is attempted first and only a proven absence
    // (NoSuch + no NOFOLLOW entry) is quiet-empty.
    fun tolerant(path: Path): JsonObject = Cancellables
        .runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
        .getOrElse { failure ->
            val genuinelyAbsent = failure is NoSuchFileException && !Files.exists(path, NOFOLLOW_LINKS)
            if (!genuinelyAbsent) {
                log(
                    "[materialize] $path unreadable (${SafeFailureText.render(failure)}) — " +
                        "global state NOT inherited by this head\n",
                )
            }
            EMPTY_JSON
        }

    fun strict(path: Path): JsonObject {
        // A symlink here is the KeyStore.entriesStrict doctrine: the entry EXISTS (dangling
        // included), and the atomic rewrite would replace the operator's link with a plain file.
        if (path.isSymbolicLink()) {
            throw IOException(
                "$path is a symlink — refusing to replace the operator's link with a materialized file; " +
                    "remove the link or point the head at a real file",
            )
        }
        return Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is NoSuchFileException && !Files.exists(path, NOFOLLOW_LINKS)
                if (!genuinelyAbsent) {
                    throw IOException(
                        "$path unreadable (${SafeFailureText.render(failure)}) — " +
                            "refusing to rewrite it; fix or remove the file",
                    )
                }
                EMPTY_JSON
            }
    }
}

// FILE SCOPE ON PURPOSE (same rule as the materializer's own copy): one immutable empty object shared
// by both read paths; a per-instance field would allocate one per reader.
private val EMPTY_JSON = JsonObject(emptyMap())
