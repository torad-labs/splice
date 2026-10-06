// NEW (2026-09-05): the per-session client window registry — what a session's status-line post
// teaches the head about the window that session's process really runs with, and (afternoon) the
// store that carries it across a daemon restart.
package splice.core.model

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider

class ClientWindowsTest(@param:TempDir private val tmp: Path) {

    private fun store(): Path = tmp.resolve("state").resolve("codex-client-windows.json")

    /** The public Path boundary: existence succeeds, while actual file IO throws the supplied exception. */
    private fun failingStore(file: Path, failure: IOException): Path {
        val provider = object : FileSystemProvider() {
            override fun getScheme(): String = "synthetic"
            override fun checkAccess(path: Path, vararg modes: AccessMode) = Unit
            override fun newByteChannel(
                path: Path,
                options: MutableSet<out OpenOption>,
                vararg attrs: FileAttribute<*>,
            ): SeekableByteChannel = throw failure
            override fun newFileSystem(
                uri: URI,
                env: MutableMap<String, *>,
            ): FileSystem = throw UnsupportedOperationException()
            override fun getFileSystem(uri: URI): FileSystem = throw UnsupportedOperationException()
            override fun getPath(uri: URI): Path = throw UnsupportedOperationException()
            override fun newDirectoryStream(
                path: Path,
                filter: DirectoryStream.Filter<in Path>,
            ): DirectoryStream<Path> = throw UnsupportedOperationException()
            override fun createDirectory(
                dir: Path,
                vararg attrs: FileAttribute<*>,
            ) = throw UnsupportedOperationException()
            override fun delete(path: Path) = throw UnsupportedOperationException()
            override fun copy(
                source: Path,
                target: Path,
                vararg options: CopyOption,
            ) = throw UnsupportedOperationException()
            override fun move(
                source: Path,
                target: Path,
                vararg options: CopyOption,
            ) = throw UnsupportedOperationException()
            override fun isSameFile(path: Path, other: Path): Boolean = throw UnsupportedOperationException()
            override fun isHidden(path: Path): Boolean = throw UnsupportedOperationException()
            override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()
            override fun <V : FileAttributeView> getFileAttributeView(
                path: Path,
                type: Class<V>,
                vararg options: LinkOption,
            ): V? = throw UnsupportedOperationException()
            override fun <A : BasicFileAttributes> readAttributes(
                path: Path,
                type: Class<A>,
                vararg options: LinkOption,
            ): A = throw UnsupportedOperationException()
            override fun readAttributes(
                path: Path,
                attributes: String,
                vararg options: LinkOption,
            ): MutableMap<String, Any> = throw UnsupportedOperationException()
            override fun setAttribute(
                path: Path,
                attribute: String,
                value: Any,
                vararg options: LinkOption,
            ) = throw UnsupportedOperationException()
        }
        val fs = object : FileSystem() {
            override fun provider(): FileSystemProvider = provider
            override fun close() = Unit
            override fun isOpen(): Boolean = true
            override fun isReadOnly(): Boolean = false
            override fun getSeparator(): String = file.fileSystem.separator
            override fun getRootDirectories(): Iterable<Path> = file.fileSystem.rootDirectories
            override fun getFileStores(): Iterable<FileStore> = file.fileSystem.fileStores
            override fun supportedFileAttributeViews(): Set<String> = emptySet()
            override fun getPath(first: String, vararg more: String): Path = throw UnsupportedOperationException()
            override fun getPathMatcher(syntaxAndPattern: String): PathMatcher = throw UnsupportedOperationException()
            override fun getUserPrincipalLookupService(): UserPrincipalLookupService = throw UnsupportedOperationException()
            override fun newWatchService(): WatchService = throw UnsupportedOperationException()
        }
        return object : Path by file {
            override fun getFileSystem(): FileSystem = fs
            override fun resolveSibling(other: String): Path = failingStore(file.resolveSibling(other), failure)
            override fun toString(): String = file.toString()
        }
    }

    @Test
    fun `anonymous filesystem failures name their category without null or exception content`() {
        val failure = object : IOException("SYNTHETIC_FILE_BYTES_MUST_NOT_BE_LOGGED") {}
        assertNull(failure::class.simpleName, "the fixture reaches both nullable class-name sites")
        val lines = mutableListOf<String>()
        val windows = ClientWindows(store = failingStore(store(), failure), log = { lines += it })
        windows.record("synthetic", 272_000)
        assertEquals(272_000L, windows.windowFor("synthetic"), "failed persistence keeps the learned window")
        assertEquals(2, lines.size)
        val withheld = "failure (message withheld: it may quote file bytes)"
        assertAll(
            { assertTrue(lines.first().contains("ignored: $withheld"), lines.first()) },
            { assertTrue(lines.last().contains("not saved to") && withheld in lines.last(), lines.last()) },
            { assertFalse(lines.any { "(null)" in it || "SYNTHETIC_FILE_BYTES" in it }, lines.toString()) },
        )
    }

    @Test
    fun `a recorded session answers with its window and an unknown one with null`() {
        val windows = ClientWindows()
        windows.record("s-old", 400_000)
        assertEquals(400_000L, windows.windowFor("s-old"))
        assertNull(windows.windowFor("s-new"))
        assertNull(windows.windowFor(null))
    }

    @Test
    fun `a later post overwrites and a blank id or non-positive window records nothing`() {
        val windows = ClientWindows()
        windows.record("s", 400_000)
        windows.record("s", 272_000)
        assertEquals(272_000L, windows.windowFor("s"))
        windows.record("", 500_000)
        windows.record(null, 500_000)
        windows.record("t", 0)
        windows.record("u", null)
        assertNull(windows.windowFor(""))
        assertNull(windows.windowFor("t"))
        assertNull(windows.windowFor("u"))
    }

    @Test
    fun `the registry is bounded - the least recently touched session goes first`() {
        val windows = ClientWindows(capacity = 2)
        windows.record("a", 1)
        windows.record("b", 2)
        windows.windowFor("a") // touch a, so b is the eldest
        windows.record("c", 3)
        assertEquals(1L, windows.windowFor("a"))
        assertNull(windows.windowFor("b"))
        assertEquals(3L, windows.windowFor("c"))
    }

    @Test
    fun `a recorded window survives a restart through the store file`() {
        val store = store()
        ClientWindows(store = store).record("s-persist", 272_000)
        assertTrue(Files.exists(store), "the registry must be written through, parent dirs included")
        val reloaded = ClientWindows(store = store)
        assertEquals(272_000L, reloaded.windowFor("s-persist"))
        assertNull(reloaded.windowFor("s-other"))
    }

    @Test
    fun `an unchanged post does not rewrite the store but a changed window does`() {
        val store = store()
        val windows = ClientWindows(store = store)
        windows.record("s", 272_000)
        Files.delete(store)
        windows.record("s", 272_000)
        assertFalse(Files.exists(store), "the same window again is not a write")
        windows.record("s", 400_000)
        assertTrue(Files.exists(store), "a changed window is")
        assertEquals(400_000L, ClientWindows(store = store).windowFor("s"))
    }

    @Test
    fun `a corrupt store is logged safely and ignored - the registry still learns and re-saves`() {
        val store = store()
        Files.createDirectories(store.parent)
        Files.writeString(store, "{not json")
        val lines = mutableListOf<String>()
        val windows = ClientWindows(store = store, log = { lines += it })
        assertNull(windows.windowFor("s"))
        assertTrue(lines.single().contains("ignored: store is not valid JSON"), lines.toString())
        assertFalse(lines.single().contains("{not json"), "parser diagnostics must not echo file bytes")
        windows.record("s", 272_000)
        assertEquals(272_000L, ClientWindows(store = store).windowFor("s"))
    }
}
