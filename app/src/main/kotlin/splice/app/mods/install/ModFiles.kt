package splice.app.mods.install

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.BasicFileAttributes

// why: bound both file-tree metadata and space, including vendored dependencies.
internal const val MOD_FILE_LIMIT = 20_000

// why: bound the total uncompressed bytes written or hashed for one mod.
internal const val MOD_BYTE_LIMIT = 200L * 1024 * 1024

/** A refusal that can be shown verbatim, never an exception from parsing a caller's JSON. */
internal class ModRefusal(message: String, val kind: Kind = Kind.INPUT, cause: IOException? = null) :
    IOException(message, cause) {
    enum class Kind { INPUT, MISSING, CONFLICT }
}

/** The same file and byte limits apply to folders, tarballs and later hash passes. */
internal class ModBudget {
    private var files = 0
    private var directories = 0
    private var bytes = 0L
    private val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

    fun file(size: Long) {
        if (++files > MOD_FILE_LIMIT) throw ModRefusal("a mod may contain at most $MOD_FILE_LIMIT files")
        if (size > MOD_BYTE_LIMIT - bytes) tooLarge()
    }

    fun directory() {
        if (++directories > MOD_FILE_LIMIT) throw ModRefusal("a mod may contain at most $MOD_FILE_LIMIT directories")
    }

    fun transfer(source: InputStream, target: OutputStream) {
        while (true) {
            val read = source.read(buffer)
            if (read < 0) return
            bytes += read
            if (bytes > MOD_BYTE_LIMIT) tooLarge()
            target.write(buffer, 0, read)
        }
    }

    private fun tooLarge(): Nothing = throw ModRefusal("a mod may contain at most 200 MiB of files")
}

/** Traversal for copying or hashing a tree. A subclass sees only regular files and dirs. */
internal abstract class ModTree(private val root: Path, protected val budget: ModBudget) : SimpleFileVisitor<Path>() {
    fun walk() {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw ModRefusal("the mod folder is missing or not a directory")
        Files.walkFileTree(root, this)
    }

    final override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (dir != root) {
            budget.directory()
            directory(root.relativize(dir))
        }
        return FileVisitResult.CONTINUE
    }

    final override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
        val relative = root.relativize(path)
        if (!attrs.isRegularFile) throw ModRefusal("$relative is not a regular file; links are not copied")
        budget.file(attrs.size())
        file(relative, path)
        return FileVisitResult.CONTINUE
    }

    protected open fun directory(relative: Path) = Unit

    protected abstract fun file(relative: Path, path: Path)
}

internal class ModCopy(source: Path, private val target: Path) : ModTree(source, ModBudget()) {
    override fun directory(relative: Path) {
        Files.createDirectory(target.resolve(relative))
    }

    override fun file(relative: Path, path: Path) {
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            ModFiles.write(input, target.resolve(relative), budget)
        }
    }
}

internal object ModFiles {
    fun write(input: InputStream, path: Path, budget: ModBudget) {
        Files.newOutputStream(path, CREATE_NEW).use { budget.transfer(input, it) }
    }
}
