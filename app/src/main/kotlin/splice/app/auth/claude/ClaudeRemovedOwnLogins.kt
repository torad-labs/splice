// NEW (Nov 19, 2026): Remove on a command's own Claude Code login. Until then the console offered no Remove on it, and
// the only act its card left a signed-out or spent login was Sign in, so a person who wanted it gone had to sign in
// first. A Remove takes the login out of splice: splice stops serving turns on it and stops listing it. Claude Code's
// own file is left as it is, so a `claude` started outside splice keeps its login (on Nov 13, 2026 a Remove that
// deleted that file left every session "Not logged in" until a /login).
//
// It is kept by ACCOUNT, never by token: Claude Code renews the token every few hours, and a removed login must not come
// back with its next token. A login signed into the place afterwards that is another account shows as usual.
package splice.app.auth.claude

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: one file per place, in the place's own store, so removing one command's login never hides another's.
private const val REMOVED_FILE = "removed-own-logins.json"

// why: what a place with no readable account is removed under: its card is gone until a login it can read appears.
private const val NO_ACCOUNT = "none"

internal class ClaudeRemovedOwnLogins(private val storeDir: Path, private val log: LogSink = LogSink { }) {
    private val file: Path get() = storeDir.resolve(REMOVED_FILE)
    private val codec = ListSerializer(String.serializer())

    /** Whether [view] is a login the person removed from splice. */
    fun removed(view: ClaudeLoginPlaceView): Boolean = key(view) in read()

    /** Whether [account] is one whose login the person removed from splice. */
    fun removedAccount(account: String): Boolean = account in read()

    /** Takes [view]'s login out of splice. */
    fun remove(view: ClaudeLoginPlaceView) {
        val all = read() + key(view)
        Files.createDirectories(storeDir)
        SecureFile.writeAtomic0600(file, Json.encodeToString(codec, all.toList()))
    }

    private fun key(view: ClaudeLoginPlaceView): String = view.identity.account?.uuid ?: NO_ACCOUNT

    /** The removed accounts; none when the file is missing, and none (said in the log) when it cannot be read, so an
     *  unreadable file never hides a login. */
    private fun read(): Set<String> {
        if (!Files.isRegularFile(file)) return emptySet()
        return try {
            Json.decodeFromString(codec, Files.readString(file)).toSet()
        } catch (e: IOException) {
            log("[claude-accounts] removed own logins unreadable (${e::class.simpleName}); none hidden\n")
            emptySet()
        } catch (e: SerializationException) {
            log("[claude-accounts] removed own logins unreadable (${e::class.simpleName}); none hidden\n")
            emptySet()
        }
    }
}
