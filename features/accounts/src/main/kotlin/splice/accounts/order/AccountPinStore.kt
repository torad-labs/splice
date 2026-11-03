// NEW: per-head Use now pins survive daemon restarts (Nov 3, 2026). A pin lived in the pool's memory alone, so the
// restart that installed #459 dropped the one Marcos had just set, and on the beta channel the daemon restarts all day.
package splice.accounts.order

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.auth.SavedPins
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The operator's per-head pin file, kept beside the other daemon state. Its own file, never a field of
 *  [ACCOUNT_ORDER_FILE]: a build from before it reads that document strictly, so a new key there would refuse it. */
public const val ACCOUNT_PINS_FILE: String = "account-pins.json"

@Serializable
private data class HeadPinsDocument(val head: String? = null, val sessions: Map<String, String> = emptyMap())

@Serializable
private data class PinsDocument(val heads: Map<String, HeadPinsDocument> = emptyMap())

/** Atomic pin persistence, every head in one document. Each replacement keeps the file it replaced as
 *  `account-pins.json.bak`, one copy. A document that cannot be read restores no pin rather than refuse the head:
 *  a pin is one press to set again, and the unreadable file is still the `.bak` after the next pin or unpin. */
public class AccountPinStore(private val file: Path) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    public fun pins(head: String): SavedPins =
        read()?.heads?.get(head)?.let { SavedPins(it.head, it.sessions) } ?: SavedPins()

    @Synchronized
    public fun set(head: String, pins: SavedPins) {
        require(head.isNotBlank())
        // Nothing to keep as the .bak when there is no file, or one splice cannot read: the press still saves.
        val previous = try {
            Files.readString(file)
        } catch (_: IOException) {
            null
        }
        val heads = read()?.heads.orEmpty() - head
        val kept = if (pins.head == null && pins.sessions.isEmpty()) {
            heads
        } else {
            heads + (head to HeadPinsDocument(pins.head, pins.sessions))
        }
        if (previous != null) {
            SecureFile.writeAtomic0600(file.resolveSibling(file.fileName.toString() + ".bak"), previous)
        }
        SecureFile.writeAtomic0600(file, json.encodeToString(PinsDocument(kept)))
    }

    private fun read(): PinsDocument? = try {
        json.decodeFromString<PinsDocument>(Files.readString(file))
    } catch (_: NoSuchFileException) {
        PinsDocument()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        // A file splice cannot read (its mode, a directory in its place) restores no pin either, rather than refuse
        // the head at boot.
        null
    }
}
