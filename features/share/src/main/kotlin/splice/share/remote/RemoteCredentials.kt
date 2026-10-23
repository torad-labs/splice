// NEW: LAN share, phase 2 (docs/specs/lan-share.md). This machine's own tokens on the machines it joined, one per
// `[remotes.<name>]`, kept in <state>/share/remotes.json through the one 0600 writer and changed under the share
// lock (ShareFileLock), since `splice remote` (the CLI) writes it while the daemon reads it. Read again on every
// request, the KeyStore's rule, so a re-join lands on the next turn with no restart. The token is password-equivalent
// on the machine that issued it and is never printed; splice.toml names the machine, never the token.
package splice.share.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import splice.share.ShareFileLock
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

/** One joined machine, by its `[remotes]` key, with the token it issued this machine, and its Sessions switch here:
 *  whether that machine may list, write into and read from this machine's sessions, off until the Sharing page turns it
 *  on, and off again after a re-join. */
@Serializable
internal data class RemoteToken(
    val name: String,
    val token: String,
    @SerialName("joined_at") val joinedAt: String,
    val sessions: Boolean = false,
) {
    // The token is the secret; a log of this record names the machine only.
    override fun toString(): String =
        "RemoteToken(name=$name, token=<redacted>, joinedAt=$joinedAt, sessions=$sessions)"
}

@Serializable
internal data class RemoteTokenBook(val remotes: List<RemoteToken> = emptyList())

public class RemotesStore(private val file: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val lock = ShareFileLock(file)

    /** The token [name] issued this machine, or null when it has none (never joined, removed, unreadable file). */
    public fun token(name: String): String? = read().remotes.firstOrNull { it.name == name }?.token

    /** Keeps [token] as [name]'s, replacing the one it held: a re-join's token supersedes the old one, which the
     *  issuing machine no longer accepts. Throws IOException, writing nothing, when the file cannot be read or
     *  locked. */
    public fun save(name: String, token: String) {
        edited { book ->
            val kept = book.remotes.filterNot { it.name == name }
            write(RemoteTokenBook(kept + RemoteToken(name, token, Instant.now().toString())))
        }
    }

    /** Whether [name]'s Sessions switch is on here; off for a machine no token is kept for. */
    public fun sessions(name: String): Boolean = read().remotes.firstOrNull { it.name == name }?.sessions == true

    /** Turns [name]'s Sessions switch [on] or off. False when no token is kept for [name]. Throws IOException, writing
     *  nothing, as [save] does. */
    public fun allowSessions(name: String, on: Boolean): Boolean = edited { book ->
        val found = book.remotes.any { it.name == name }
        if (found) write(RemoteTokenBook(book.remotes.map { if (it.name == name) it.copy(sessions = on) else it }))
        found
    }

    /** False when no token is kept for [name]. Throws IOException, writing nothing, as [save] does. */
    public fun remove(name: String): Boolean = edited { book ->
        val kept = book.remotes.filterNot { it.name == name }
        val found = kept.size != book.remotes.size
        if (found) write(RemoteTokenBook(kept))
        found
    }

    /** The machines a token is kept for, by `[remotes]` key. */
    public fun names(): List<String> = read().remotes.map { it.name }

    /** A missing file is no tokens; one that does not parse also reads as none, and every relay then fails in words. */
    private fun read(): RemoteTokenBook {
        val text = PathProbe.text(file) ?: return RemoteTokenBook()
        return Cancellables.runCatchingCancellable { json.decodeFromString(RemoteTokenBook.serializer(), text) }
            .fold(onSuccess = { it }, onFailure = { RemoteTokenBook() })
    }

    /** Only a missing file is an empty book here: one that cannot be read or does not parse throws, so a change never
     *  writes over tokens it could not see. */
    private inline fun <T> edited(change: (RemoteTokenBook) -> T): T = lock.edit {
        val text = try {
            Files.readString(file)
        } catch (_: NoSuchFileException) {
            null
        }
        val book = text?.let {
            Cancellables.runCatchingCancellable { json.decodeFromString(RemoteTokenBook.serializer(), it) }
                .getOrElse { throw FileSystemException("$file", null, "does not parse as a token book") }
        } ?: RemoteTokenBook()
        change(book)
    }

    private fun write(book: RemoteTokenBook) {
        SecureFile.writeAtomic0600(file, json.encodeToString(RemoteTokenBook.serializer(), book) + "\n")
    }
}

/**
 * A `splice-remote` provider's credential: this machine's token on [remote], presented as a bearer to that machine's
 * share listener, which swaps it for its own head's turn key. Read from [store] on every call, so `splice remote` can
 * renew it under a running daemon. A refresh is the same read: there is no refresh token, and a revoked token stays
 * refused until `splice remote add` joins again, which the turn's error says.
 */
public class RemoteAuthProvider(private val store: RemotesStore, private val remote: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials? = store.token(remote)?.let { Credentials.ApiKey(it) }

    override suspend fun refresh(): Credentials? = credentials()

    override suspend fun describe(): AuthDescription = AuthDescription(
        present = store.token(remote) != null,
        kind = AuthKind.SpliceRemote.wire,
        fields = mapOf("remote" to remote),
    )
}
