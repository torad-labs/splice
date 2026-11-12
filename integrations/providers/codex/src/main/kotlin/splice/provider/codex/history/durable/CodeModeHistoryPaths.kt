package splice.provider.codex.history.durable

import splice.core.perf.InputDigest
import splice.core.util.JSONL_LOCK_SUFFIX
import java.nio.file.Path

/** Stable history filenames share one identity digest across live and legacy authority. */
internal class CodeModeHistoryPaths(root: Path) {
    val directory: Path = root.resolve("history")

    /** The digest a conversation's files are named by. */
    fun digest(key: String): String = InputDigest.hex(key)

    fun journal(key: String): Path = directory.resolve(digest(key) + ".jsonl")

    fun legacy(key: String): Path = directory.resolve(digest(key) + ".json")

    /** The sibling lock file JsonlSink.appendLine takes beside [file]. */
    fun lock(file: Path): Path = file.resolveSibling("${file.fileName}$JSONL_LOCK_SUFFIX")
}
