package splice.configuration.add

import java.nio.file.Path

/** The config file an add edits: its [path], the text it held when the add was prepared ([existing]) and the
 *  tables the add appends to it ([appended]). */
internal data class AddFileEdit(
    val path: Path,
    val existing: String,
    val appended: String,
)
