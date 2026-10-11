// NEW: Oct 10, 2026 — console BUILD row "a key is its own account" (Marcos, Oct 8; the date from fin; Replaced from
// hitstop, Oct 9). Each key variable keeps the keys splice has seen under it, by fingerprint, with the time splice first
// saw each one. The last is the key in use; the ones before it were replaced and keep their own date, so a rotated key
// reads as a new account and the old one stays on the page marked Replaced. The time is when splice first saw the key,
// not when it was stored: a key from the environment or a file was never stored by splice.
//
// The ledger holds fingerprints and times only, never a key. It sits beside the key store (key-ledger.json, 0600).
package splice.accounts.keys

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** One key splice has seen under a variable: its fingerprint and when splice first saw it, null when splice could not
 *  record that day (a ledger it cannot write). */
@Serializable
internal data class KeySeen(
    val fingerprint: String,
    @SerialName("first_seen_epoch_seconds") val firstSeen: Long?,
)

/** The key in use under a variable, if any, and the keys it replaced, newest first. */
internal data class KeyHistory(val current: KeySeen?, val replaced: List<KeySeen>)

internal class KeyLedger(
    private val file: Path,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var writeRefused = false

    /** Records that [name] reads the key with [fingerprint] now (null: no key), and answers its history. A key seen
     *  before under the same name comes back with its first date. */
    @Synchronized
    fun observe(name: String, fingerprint: String?): KeyHistory {
        val all = read()
        val seen = all[name].orEmpty()
        val updated = when {
            fingerprint == null || seen.lastOrNull()?.fingerprint == fingerprint -> seen
            else -> seen.filter { it.fingerprint != fingerprint } +
                (seen.firstOrNull { it.fingerprint == fingerprint } ?: KeySeen(fingerprint, now()))
        }
        val kept = if (updated == seen || write(all + (name to updated))) updated else unrecorded(seen, updated)
        // With no key in use, the last one was removed, not replaced: only the ones before it read as Replaced.
        return KeyHistory(kept.lastOrNull()?.takeIf { fingerprint != null }, kept.dropLast(1).reversed())
    }

    // A ledger splice cannot write (a read-only config folder) never stops the key list: it holds dates, not keys.
    private fun write(ledger: Map<String, List<KeySeen>>): Boolean =
        Cancellables.runCatchingCancellable {
            Files.createDirectories(file.parent)
            SecureFile.writeAtomic0600(file, json.encodeToString(ledger))
        }.onFailure {
            if (!writeRefused) {
                writeRefused = true
                log(
                    "[control] keys: the key ledger at ${LogSafe.str(file.toString())} could not be written " +
                        "(${LogSafe.str(SafeFailureText.render(it))}); a key first seen now shows no day\n",
                )
            }
        }.isSuccess

    /** The history as it would read had nothing been recorded now: a key seen for the first time has no day. */
    private fun unrecorded(seen: List<KeySeen>, updated: List<KeySeen>): List<KeySeen> =
        updated.map { key -> if (seen.any { it.fingerprint == key.fingerprint }) key else key.copy(firstSeen = null) }

    private fun now(): Long = Instant.ofEpochMilli(clock()).epochSecond

    // An unreadable ledger starts over rather than refusing the key routes: it holds dates, never a credential.
    private fun read(): Map<String, List<KeySeen>> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return Cancellables.runCatchingCancellable {
            json.decodeFromString<Map<String, List<KeySeen>>>(Files.readString(file))
        }.onFailure {
            log(
                "[control] keys: the key ledger at ${LogSafe.str(file.toString())} could not be read " +
                    "(${LogSafe.str(SafeFailureText.render(it))}); starting over\n",
            )
        }.getOrNull().orEmpty()
    }
}
