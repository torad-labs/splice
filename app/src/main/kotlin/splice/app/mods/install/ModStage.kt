package splice.app.mods.install

import splice.app.mods.MANIFEST
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** Build the new copy beside the old one. A failed validation or publication leaves the old copy in place. */
internal class ModStage(installed: Path) : AutoCloseable {
    private val directory = Files.createTempDirectory(installed, ".install-")
    private val payload = directory.resolve("payload")
    val hash: Path = directory.resolve("files.sha256.json")
    private var preserve = false

    fun unpack(source: Path): Path {
        Files.createDirectory(payload)
        if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
            ModCopy(source, payload).walk()
        } else {
            ModTarball(payload).unpack(source)
        }
        return packageRoot().also(ModPrivateDir::create)
    }

    private fun packageRoot(): Path {
        if (Files.exists(payload.resolve(MANIFEST))) return payload
        val wrapped = Files.list(payload).use { it.limit(2).toList().singleOrNull() }
        return wrapped?.takeIf { Files.isRegularFile(it.resolve(MANIFEST)) } ?: payload
    }

    inline fun publish(mod: InstalledMod, payload: Path?, commit: () -> Unit) {
        swapped(mod.copy, payload) {
            swapped(mod.hashFile, if (payload == null) null else hash, commit)
        }
    }

    inline fun accept(mod: InstalledMod, commit: () -> Unit) = swapped(mod.hashFile, hash, commit)

    private inline fun swapped(target: Path, replacement: Path?, commit: () -> Unit) {
        swap(target, replacement)
        try {
            commit()
        } catch (failure: IOException) {
            undo(target, failure)
            throw failure
        }
    }

    private fun swap(target: Path, replacement: Path?) {
        val previous = directory.resolve("previous-${target.fileName}")
        val hadPrevious = Files.exists(target, NOFOLLOW_LINKS)
        if (hadPrevious) move(target, previous)
        try {
            if (replacement != null) move(replacement, target)
        } catch (failure: IOException) {
            if (hadPrevious) restore(target, failure)
            throw failure
        }
    }

    private fun undo(target: Path, failure: IOException) {
        try {
            if (Files.exists(target, NOFOLLOW_LINKS)) move(target, directory.resolve("discard-${target.fileName}"))
        } catch (rollback: IOException) {
            keepRecovery(failure, rollback)
            return
        }
        restore(target, failure)
    }

    private fun restore(target: Path, failure: IOException) {
        val previous = directory.resolve("previous-${target.fileName}")
        if (!Files.exists(previous, NOFOLLOW_LINKS)) return
        try {
            move(previous, target)
        } catch (rollback: IOException) {
            keepRecovery(failure, rollback)
        }
    }

    private fun keepRecovery(failure: IOException, rollback: IOException) {
        preserve = true
        failure.addSuppressed(IOException("could not restore the previous copy; it is kept at $directory", rollback))
    }

    private fun move(from: Path, to: Path) {
        Files.move(from, to, ATOMIC_MOVE)
    }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        if (!preserve) directory.deleteRecursively()
    }
}

/** A mod may contain the author's code or vendored dependencies; keep the whole copy beneath private directories. */
internal object ModPrivateDir {
    fun create(path: Path) {
        SecureFile.ownerOnlyDirectory(path)?.let { throw IOException("could not make $path private: $it") }
    }
}
