package splice.app.mods.install

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private val regularFiles = setOf(TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM)

/** Expands a tar or tar.gz into a private, empty directory without following archive links. */
internal class ModTarball(private val target: Path) {
    private val budget = ModBudget()

    fun unpack(path: Path) {
        try {
            Files.newInputStream(path, NOFOLLOW_LINKS).buffered().use { source ->
                TarArchiveInputStream(payload(source)).use(::extract)
            }
        } catch (refused: ModRefusal) {
            throw refused
        } catch (unreadable: IOException) {
            throw ModRefusal("could not read or unpack the tarball: ${unreadable.message}", cause = unreadable)
        }
    }

    private fun payload(source: BufferedInputStream): InputStream {
        // A gzip header starts with two magic bytes; the filename need not have an extension.
        source.mark(2)
        val signature = source.readNBytes(2)
        source.reset()
        return if (GzipCompressorInputStream.matches(signature, signature.size)) {
            GzipCompressorInputStream(source)
        } else {
            source
        }
    }

    private fun extract(archive: TarArchiveInputStream) {
        while (true) {
            val entry = archive.nextEntry ?: return
            val name = entry.name
            val path = destination(name)
            when {
                entry.isDirectory && entry.size == 0L -> directory(path)
                !entry.isSparse && entry.linkFlag in regularFiles -> {
                    budget.file(entry.size)
                    directory(path.parent)
                    ModFiles.write(archive, path, budget)
                }
                else -> throw ModRefusal("$name is not a regular file or directory; links are not extracted")
            }
        }
    }

    private fun destination(name: String): Path {
        val parts = name.split('/')
        val rooted = name.startsWith('/') || name.getOrNull(1) == ':'
        val unportable = name.isEmpty() || '\\' in name
        val escapes = rooted || ".." in parts
        if (escapes || unportable) {
            throw ModRefusal("$name is not a path inside the tarball's mod folder")
        }
        return target.resolve(name).normalize()
    }

    private fun directory(path: Path) {
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) return
        // Count implicit parents too, so many deep entries can't create unbounded metadata.
        val missing = generateSequence(path) { it.parent }.takeWhile { !Files.exists(it, NOFOLLOW_LINKS) }.toList()
        missing.asReversed().forEach {
            budget.directory()
            Files.createDirectory(it)
        }
    }
}
