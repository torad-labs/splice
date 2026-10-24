// NEW: LAN share, phase 1 (docs/specs/lan-share.md). The machines that joined this one, each with its own
// token. The token itself is shown once, in the join's answer; this file keeps only its SHA-256 beside the
// name and the address the machine joined from, in <state>/share/clients.json through the one 0600 writer.
// Read again on every request, the KeyStore's rule: the file is a few lines, and a `splice share revoke` run
// in another process takes effect on the next turn with no restart. A join (the daemon) and a revoke (the CLI)
// are separate processes, so each change is read, made and written under the sibling clients.json.lock (the
// KeyStore's SH-11 shape): a revoke can no longer be undone by a join that read the book before it.
package splice.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import splice.share.remote.RemoteNames
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat

/** One joined machine. [tokenSha256] is the hex SHA-256 of its token; the token is never stored. [sessions] is its
 *  Sessions switch: whether it may list, write into and read from this machine's sessions, off until the Sharing page
 *  turns it on, and off again when the machine joins again. */
@Serializable
public data class ShareClient(
    public val name: String,
    @SerialName("token_sha256") public val tokenSha256: String,
    public val address: String,
    @SerialName("joined_at") public val joinedAt: String,
    public val sessions: Boolean = false,
) {
    /** Whether [asked] names this machine, however it is spelled: both read as RemoteNames reads a machine's name. */
    internal fun named(asked: String): Boolean = RemoteNames.of(name) == RemoteNames.of(asked)
}

@Serializable
internal data class ShareClientBook(val clients: List<ShareClient> = emptyList())

public class ShareClients(private val file: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val random = SecureRandom()
    private val lock = ShareFileLock(file)
    private val hex = HexFormat.of()

    /** When each machine was last heard from (ShareActivity), kept beside this book. A revoked machine's line stays
     *  until the daemon next writes it; every reader lists only the machines this book still holds. */
    public val activity: ShareActivity = ShareActivity(file.resolveSibling("activity.json"))

    /** A new token for the machine that [asked] to join, replacing any it held: a machine that imports again gets a
     *  fresh one and the old one stops working. It is booked under the name a machine that joins it back keys it by in
     *  `[remotes]` (RemoteNames), so its sessions here, offered by it or looked at from here, are one machine's, and it
     *  takes the place of an entry phase 1 booked under the name as given, capitals and all. A machine that is not
     *  booked now (new, or revoked) starts with no activity: what a revoked machine of that name did is not its own.
     *  Throws IOException, writing nothing, when the book cannot be read or locked. */
    @Synchronized
    internal fun join(asked: String, address: String): String = edited { book ->
        val name = RemoteNames.of(asked)
        if (book.clients.none { it.named(name) }) activity.forget(name)
        val token = hex.formatHex(ByteArray(SHARE_TOKEN_BYTES).also(random::nextBytes))
        val joined = ShareClient(name, digest(token), address, Instant.now().toString())
        write(ShareClientBook(book.clients.filterNot { it.named(name) } + joined))
        token
    }

    /** The machine [token] belongs to, compared in constant time; null for no token or an unknown one. */
    internal fun holder(token: String?): ShareClient? {
        val presented = token?.let { digest(it).toByteArray(UTF_8) } ?: return null
        return read().clients.firstOrNull { MessageDigest.isEqual(it.tokenSha256.toByteArray(UTF_8), presented) }
    }

    /** Turns [name]'s Sessions switch [on] or off, [name] spelled any way RemoteNames reads as the machine's. False
     *  when no machine has that name. Throws IOException, writing nothing, as [join] does. */
    @Synchronized
    public fun allowSessions(name: String, on: Boolean): Boolean = edited { book ->
        val found = book.clients.any { it.named(name) }
        if (found) write(ShareClientBook(book.clients.map { if (it.named(name)) it.copy(sessions = on) else it }))
        found
    }

    /** False when no machine has that name, spelled any way RemoteNames reads as the machine's. Throws IOException,
     *  writing nothing, as [join] does. */
    @Synchronized
    public fun revoke(name: String): Boolean = edited { book ->
        val kept = book.clients.filterNot { it.named(name) }
        val found = kept.size != book.clients.size
        if (found) write(ShareClientBook(kept))
        found
    }

    public fun all(): List<ShareClient> = read().clients

    /** A missing file is no machines. One that does not parse also reads as none: a damaged file must lock
     *  machines out, never let an unknown one in. */
    private fun read(): ShareClientBook {
        val text = PathProbe.text(file) ?: return ShareClientBook()
        return Cancellables.runCatchingCancellable { json.decodeFromString(ShareClientBook.serializer(), text) }
            .fold(onSuccess = { it }, onFailure = { ShareClientBook() })
    }

    /** [change] over the book as it is on disk, under the cross-process lock. Unlike [read], only a missing file
     *  is an empty book here: one that cannot be read or does not parse throws, so a change never writes over
     *  registrations it could not see. */
    private inline fun <T> edited(crossinline change: (ShareClientBook) -> T): T = lock.edit { change(onDisk()) }

    private fun onDisk(): ShareClientBook {
        val text = try {
            Files.readString(file)
        } catch (_: NoSuchFileException) {
            return ShareClientBook()
        }
        return Cancellables.runCatchingCancellable { json.decodeFromString(ShareClientBook.serializer(), text) }
            .getOrElse { throw FileSystemException("$file", null, "does not parse as a client book") }
    }

    private fun write(book: ShareClientBook) {
        SecureFile.writeAtomic0600(file, json.encodeToString(ShareClientBook.serializer(), book) + "\n")
    }

    private fun digest(token: String): String =
        hex.formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(UTF_8)))
}

/** The cross-process lock every share file is changed under: a sibling `<file>.lock` (0600) taken with a bounded
 *  tryLock poll, the KeyStore's SH-11 shape. The daemon and the `splice share` / `splice remote` verbs are separate
 *  processes, so a change is read, made and written while it is held; a same-JVM holder waits like a peer process. */
internal class ShareFileLock(val file: Path) {
    inline fun <T> edit(change: () -> T): T {
        val lockFile = file.resolveSibling("${file.fileName}.lock")
        Files.createDirectories(lockFile.toAbsolutePath().parent)
        val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val mode = SecureFile.CREATE_0600
        val channel = if (mode == null) {
            FileChannel.open(lockFile, options)
        } else {
            FileChannel.open(lockFile, options, mode)
        }
        return channel.use { locked(it).use { change() } }
    }

    fun locked(channel: FileChannel): FileLock {
        val deadline = System.currentTimeMillis() + SHARE_LOCK_WAIT_MS
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            if (System.currentTimeMillis() >= deadline) {
                throw FileSystemException("$file", null, "locked by another splice for over ${SHARE_LOCK_WAIT_MS}ms")
            }
            Thread.sleep(SHARE_LOCK_POLL_MS)
        }
    }
}

// why: 256 random bits, the strength of the SHA-256 it is stored as; the token is all a joined machine presents,
// and the listener faces a whole network.
private const val SHARE_TOKEN_BYTES = 32

// why: a change holds the lock for one parse and one atomic write, microseconds; five seconds of waiting is a
// wedged peer, and the change fails in words rather than writing a book it did not read under the lock.
private const val SHARE_LOCK_WAIT_MS = 5_000L

// why: how often a waiting change looks at the lock again; holds are microseconds, so 25 ms is a short wait that
// still does not spin.
private const val SHARE_LOCK_POLL_MS = 25L
