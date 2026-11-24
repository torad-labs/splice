package splice.app.modes

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Collections
import java.util.Enumeration
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.CRC32

/**
 * One parent-first loader per pinned archive. API types, including their JSON types, keep the identity of the
 * daemon's classpath. Mods can supply classes missing from that classpath. Mode registration and files
 * belong only to this archive. No file URLs are opened: lazy class/resource reads use the same bytes that were pinned,
 * including in a cold, read-only preflight. The loader has no open archive or file handles to keep alive.
 */
internal class ModJarClassLoader(
    parent: ClassLoader,
    private val entries: Map<String, ByteArray>,
) : URLClassLoader(emptyArray(), parent) {
    override fun findClass(name: String): Class<*> {
        val bytes = entries[classEntry(name)] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun getResource(name: String): URL? = when {
        isolated(name) -> findResource(name)
        else -> super.getResource(name)
    }

    override fun getResources(name: String): Enumeration<URL> = when {
        isolated(name) -> findResources(name)
        else -> super.getResources(name)
    }

    override fun findResource(name: String): URL? = entries[name]?.let { bytes ->
        URL.of(URI("splice-mod", null, "/$name", null), ModResource(bytes))
    }

    override fun findResources(name: String): Enumeration<URL> =
        Collections.enumeration(listOfNotNull(findResource(name)))

    private fun classEntry(name: String): String = name.replace('.', '/') + ".class"

    private fun isolated(name: String): Boolean = name.startsWith("splice-modes/") || name == MODE_SERVICE
}

/** Reads through the central directory, checking each entry's actual length and CRC before exposing it. */
internal object ModJarEntries {
    fun read(bytes: ByteArray): Map<String, ByteArray> = ZipFile.builder().setByteArray(bytes).get().use { zip ->
        val files = linkedMapOf<String, ByteArray>()
        var remaining = MAX_EXPANDED_BYTES
        for (entry in zip.entries) {
            checkPackage(entry.name)
            val data = entryBytes(zip, entry, remaining)
            if (files.put(entry.name, data) != null) throw IOException("duplicate mod archive entry")
            remaining -= data.size
        }
        val multiRelease = files[JarFile.MANIFEST_NAME]?.let(::multiRelease) == true
        if (multiRelease) files + runtimeEntries(files) else files
    }

    private fun checkPackage(name: String) {
        val resource = if (name.startsWith(VERSIONS)) name.removePrefix(VERSIONS).substringAfter('/', "") else name
        if (PROTECTED_PACKAGES.any(resource::startsWith)) {
            throw ModFileFailure("archive entries may not use the reserved splice.core or splice.modes.api packages")
        }
    }

    private fun entryBytes(zip: ZipFile, entry: ZipArchiveEntry, remaining: Int): ByteArray {
        if (entry.size !in 0..remaining.toLong()) throw IOException("invalid mod entry size")
        val bytes = zip.getInputStream(entry).use { it.readNBytes(entry.size.toInt() + 1) }
        val crc = CRC32().apply { update(bytes) }.value
        if (bytes.size.toLong() != entry.size || crc != entry.crc) {
            throw IOException("invalid mod entry CRC or size")
        }
        return bytes
    }

    private fun multiRelease(bytes: ByteArray): Boolean {
        val attributes = Manifest(ByteArrayInputStream(bytes)).mainAttributes
        // Dependencies must be packaged in the verified archive, not resolved from adjacent, unpinned files.
        if (attributes.containsKey(Attributes.Name.CLASS_PATH)) {
            throw ModFileFailure("manifest Class-Path is not supported; bundle mod dependencies in the pinned JAR")
        }
        return attributes.getValue(Attributes.Name.MULTI_RELEASE).equals("true", ignoreCase = true)
    }

    private fun runtimeEntries(files: Map<String, ByteArray>): Map<String, ByteArray> {
        val releases = sortedMapOf<Int, MutableMap<String, ByteArray>>()
        val supported = FIRST_VERSIONED_RELEASE..JarFile.runtimeVersion().feature()
        for ((name, bytes) in files.filterKeys { it.startsWith(VERSIONS) }) {
            val path = name.removePrefix(VERSIONS)
            val version = path.substringBefore('/').toIntOrNull()?.takeIf { it in supported } ?: continue
            val resource = path.substringAfter('/', "")
            if (resource.isNotEmpty() && !resource.startsWith("META-INF/")) {
                releases.getOrPut(version, ::linkedMapOf)[resource] = bytes
            }
        }
        // Higher compatible releases overwrite lower ones; META-INF itself is never versioned.
        return buildMap { releases.values.forEach(::putAll) }
    }
}

private class ModResource(private val bytes: ByteArray) : URLStreamHandler() {
    override fun openConnection(url: URL): URLConnection = object : URLConnection(url) {
        override fun connect() = Unit

        override fun getInputStream(): ByteArrayInputStream = ByteArrayInputStream(bytes)
    }
}

private const val MODE_SERVICE = "META-INF/services/splice.modes.api.Mode"
private const val VERSIONS = "META-INF/versions/"

// Multi-release JAR entries begin with Java 9.
private const val FIRST_VERSIONED_RELEASE = 9

// Each loaded archive is retained in memory; cap expansion even for a small, highly compressed JAR.
private const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024
private val PROTECTED_PACKAGES = listOf("splice/core/", "splice/modes/api/")
