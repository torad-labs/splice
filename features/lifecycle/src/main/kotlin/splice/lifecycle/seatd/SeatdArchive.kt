package splice.lifecycle.seatd

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarConstants.DEFAULT_RCDSIZE
import org.apache.commons.compress.archivers.tar.TarConstants.LF_NORMAL
import org.apache.commons.compress.archivers.tar.TarConstants.LF_OFFSET
import org.apache.commons.compress.archivers.tar.TarConstants.LF_OLDNORM
import org.apache.commons.compress.archivers.tar.TarConstants.NAMELEN
import splice.lifecycle.upgrade.TRANSFER_BUFFER_BYTES
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** Reads raw records so metadata entries are rejected, not interpreted or hidden by a higher-level tar iterator. */
internal class SeatdArchive {
    fun unpack(archive: Path, staging: Path): Boolean = try {
        Files.newInputStream(archive).use { raw ->
            GZIPInputStream(raw).use { input -> unpack(input, staging) }
        }
    } catch (_: ZipException) {
        false
    } catch (_: EOFException) {
        false
    }

    private fun unpack(input: InputStream, staging: Path): Boolean {
        val missing = mutableSetOf(SEATD_PIPE_BINARY, "LICENSE", "THIRD_PARTY_NOTICES.md")
        var header = input.readNBytes(DEFAULT_RCDSIZE)
        while (!zeroRecord(header)) {
            val entry = regularFile(header) ?: return false
            if (!missing.remove(entry.name) || !copy(input, staging.resolve(entry.name), entry.size)) return false
            header = input.readNBytes(DEFAULT_RCDSIZE)
        }
        return missing.isEmpty() && zeroRecord(input.readNBytes(DEFAULT_RCDSIZE)) && onlyZerosRemain(input)
    }

    private fun regularFile(header: ByteArray): TarArchiveEntry? {
        if (header.size != DEFAULT_RCDSIZE || !normalType(header[LF_OFFSET])) return null
        val rawName = header.decodeToString(endIndex = NAMELEN).substringBefore('\u0000')
        return try {
            TarArchiveEntry(header).takeIf { it.isCheckSumOK && it.size >= 0 && it.name == rawName }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun normalType(flag: Byte): Boolean = flag == LF_NORMAL || flag == LF_OLDNORM

    private fun copy(input: InputStream, target: Path, size: Long): Boolean {
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        Files.newOutputStream(target, CREATE_NEW).use { file ->
            var remaining = size
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) return false
                file.write(buffer, 0, count)
                remaining -= count.toLong()
            }
        }
        val padding = ((DEFAULT_RCDSIZE - size % DEFAULT_RCDSIZE) % DEFAULT_RCDSIZE).toInt()
        val bytes = input.readNBytes(padding)
        return bytes.size == padding && bytes.all { it.toInt() == 0 }
    }

    private fun zeroRecord(header: ByteArray): Boolean =
        header.size == DEFAULT_RCDSIZE && header.all { it.toInt() == 0 }

    private fun onlyZerosRemain(input: InputStream): Boolean {
        val bytes = ByteArray(TRANSFER_BUFFER_BYTES)
        var count = input.read(bytes)
        while (count >= 0) {
            if ((0 until count).any { bytes[it].toInt() != 0 }) return false
            count = input.read(bytes)
        }
        return true
    }
}
