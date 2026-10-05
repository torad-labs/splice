// NEW: V4-457 source-derived inventory of JSONL days and binary body companions.
package splice.core.storage

import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Source-derived inventory of real day files; neither sizes nor lines follow a symlink target. */
internal class DayFileInventory(prefix: String) {
    private val namePattern =
        Regex("${Regex.escape(prefix)}-(\\d{4}-\\d{2}-\\d{2})\\.jsonl(?:\\.lock|\\.1|\\.bodies2?)?")
    private val packs = listOf(DAY_BODY_SUFFIX, DAY_BODY_V2_SUFFIX)

    fun inventory(days: List<Pair<LocalDate, Path>>, retentionDays: Int): DayInventory {
        require(retentionDays > 0)
        val kept = days.mapNotNull { (date, file) ->
            val siblings = listOf("${file.fileName}.1", "${file.fileName}") + packs.map { "${file.fileName}$it" }
            val content = siblings.map { file.resolveSibling(it) }
                .mapNotNull { path -> regularSize(path)?.let { path to it } }
            if (content.isEmpty()) null else date to content
        }
        return DayInventory(
            days = kept.size,
            rows = kept.sumOf { (_, content) ->
                content.filterNot { (file, _) -> packs.any { file.fileName.toString().endsWith(it) } }
                    .sumOf { (file, _) -> countLines(file) }
            },
            bytes = kept.sumOf { (_, content) -> content.sumOf { (_, size) -> size } },
            oldest = kept.firstOrNull()?.first,
            agesOut = kept.lastOrNull()?.first?.plusDays(retentionDays.toLong()),
        )
    }

    fun regularSize(file: Path): Long? = try {
        val attrs = Files.readAttributes(file, "basic:size,isRegularFile", LinkOption.NOFOLLOW_LINKS)
        when {
            attrs["isRegularFile"] == true -> attrs["size"] as? Long
            Files.isSymbolicLink(file) -> null
            else -> throw IOException("not a regular day file: $file")
        }
    } catch (_: NoSuchFileException) {
        null
    }

    /** Only a calendar day carried by a regular file or a removable symlink is a store entry. */
    fun dateOf(file: Path): LocalDate? {
        val name = namePattern.matchEntire(file.fileName.toString())?.groupValues?.get(1) ?: return null
        val date = try {
            LocalDate.parse(name)
        } catch (_: DateTimeParseException) {
            null
        }
        if (date != null) {
            val attrs = try {
                Files.readAttributes(file, "basic:isRegularFile,isSymbolicLink", LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return null
            }
            if (attrs["isRegularFile"] != true && attrs["isSymbolicLink"] != true) {
                throw IOException("not a day file: $file")
            }
        }
        return date
    }

    private fun countLines(file: Path): Long {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), decoder)
            .useLines { lines -> lines.fold(0L) { count, _ -> count + 1 } }
    }
}
