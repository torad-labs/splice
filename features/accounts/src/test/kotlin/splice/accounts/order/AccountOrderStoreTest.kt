// NEW: policy round trips isolate heads and preserve evidence when an existing document is broken.
package splice.accounts.order

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AccountOrderStoreTest {
    @Test
    fun `head orders survive reconstruction and edits preserve siblings with a backup`(@TempDir root: Path) {
        val file = root.resolve("order.json")
        val store = AccountOrderStore(file)
        assertTrue(store.order("one").isEmpty())
        store.set("one", listOf("higher", "lower"))
        val first = Files.readString(file)
        store.set("two", listOf("alternate", "primary"))
        val restored = AccountOrderStore(file)
        assertEquals(listOf("higher", "lower"), restored.order("one"))
        assertEquals(listOf("alternate", "primary"), restored.order("two"))
        Files.list(root).use { paths ->
            val backup = paths.filter { it.fileName.toString().startsWith("order.json.bak-") }.findFirst().orElseThrow()
            assertEquals(first, Files.readString(backup))
        }
        store.set("one", emptyList())
        assertTrue(AccountOrderStore(file).order("one").isEmpty())
        assertEquals(listOf("alternate", "primary"), restored.order("two"))
    }

    @Test
    fun `malformed or invalid policy never replaces the prior document`(@TempDir root: Path) {
        val file = root.resolve("order.json")
        Files.writeString(file, "{broken")
        val store = AccountOrderStore(file)
        assertThrows<IllegalArgumentException> { store.set("one", listOf("primary")) }
        assertEquals("{broken", Files.readString(file))
        Files.delete(file)
        store.set("one", listOf("primary"))
        val before = Files.readString(file)
        assertThrows<IllegalArgumentException> { store.set("one", listOf("primary", "primary")) }
        assertThrows<IllegalArgumentException> { store.set("one", listOf("")) }
        assertEquals(before, Files.readString(file))
    }
}
