// NEW: per-head account priority survives daemon restarts without changing credentials or topology.
package splice.accounts.order

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The operator's per-head order file, kept beside the other daemon state. */
public const val ACCOUNT_ORDER_FILE: String = "account-order.json"

@Serializable
private data class OrderDocument(val orders: Map<String, List<String>> = emptyMap())

/** Atomic policy persistence. An unreadable document is a failure, never an empty replacement. */
public class AccountOrderStore(private val file: Path) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    public fun order(head: String): List<String> = read().orders[head].orEmpty()

    @Synchronized
    public fun set(head: String, labels: List<String>) {
        require(head.isNotBlank())
        require(labels.none(String::isBlank) && labels.distinct().size == labels.size)
        val document = read()
        val next = document.copy(orders = document.orders + (head to labels.toList()))
        val previous = try {
            Files.readString(file)
        } catch (_: NoSuchFileException) {
            null
        }
        if (previous != null) {
            SecureFile.writeAtomic0600(
                file.resolveSibling(file.fileName.toString() + ".bak-" + System.currentTimeMillis()),
                previous,
            )
        }
        SecureFile.writeAtomic0600(file, json.encodeToString(next))
    }

    private fun read(): OrderDocument = try {
        json.decodeFromString(Files.readString(file))
    } catch (_: NoSuchFileException) {
        OrderDocument()
    }
}
