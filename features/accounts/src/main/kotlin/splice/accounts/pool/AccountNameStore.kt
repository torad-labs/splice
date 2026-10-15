// NEW: labels only (Eli's ruling via splice-lead, 2026-10-12). The names a person gives logins, kept in splice's own
// state as ref -> name (with the number each unnamed login keeps), so every kind of login can be named and renamed (his native Claude login included) without
// splice ever writing a credential file it does not own.
package splice.accounts.pool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.auth.AccountRef
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The names file, kept beside the other daemon state. */
public const val ACCOUNT_NAMES_FILE: String = "account-names.json"

// why: the longest name a card's name slot holds whole, the same bound a stored pool label has (MAX_LABEL_CHARS).
private const val MAX_NAME_CHARS = 48

@Serializable
private data class NamesDocument(
    val names: Map<String, String> = emptyMap(),
    /** The number each unnamed login was given within its kind, by ref (AccountNumbers): kept, never renumbered. */
    val numbers: Map<String, AccountNumber> = emptyMap(),
)

/** Why a name was not kept, in the words the console shows under the field. */
internal class AccountNameRefused(message: String) : IllegalArgumentException(message)

/** Atomic persistence of the names a person chose. An unreadable document is a failure, never an empty replacement. */
public class AccountNameStore(private val file: Path) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val refShape = Regex("^[0-9a-f]{${AccountRef.CHARS}}$")

    public fun names(): Map<String, String> = read().names

    /** Names the login whose ref is [ref]. A blank [name] forgets the chosen one: the row reads its default again. */
    @Synchronized
    public fun set(ref: String, name: String) {
        val trimmed = name.trim()
        val refused = when {
            !refShape.matches(ref) -> "Not an account"
            trimmed.length > MAX_NAME_CHARS -> "Too long"
            trimmed.any(Char::isISOControl) -> "Not a name"
            else -> null
        }
        refused?.let { throw AccountNameRefused(it) }
        val document = read()
        write(document.copy(names = if (trimmed.isEmpty()) document.names - ref else document.names + (ref to trimmed)))
    }

    /** What each login of [kinds] reads when no one named it, "Max" or "Max 2", by ref. A login seen for the first
     *  time takes the next number of its kind and keeps it; the file is written only when one was handed out. */
    @Synchronized
    public fun numbered(kinds: Map<String, AccountKind>): Map<String, String> {
        val document = read()
        val numbers = AccountNumbers(document.numbers).assign(kinds)
        if (numbers != document.numbers) write(document.copy(numbers = numbers))
        return kinds.keys.associateWith { numbers.getValue(it).shown() }
    }

    private fun write(document: NamesDocument) = SecureFile.writeAtomic0600(file, json.encodeToString(document))

    private fun read(): NamesDocument = try {
        json.decodeFromString(Files.readString(file))
    } catch (_: NoSuchFileException) {
        NamesDocument()
    }
}
