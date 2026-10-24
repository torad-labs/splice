package splice.provider.codex.history.durable

import splice.core.perf.InputDigest
import java.nio.file.Path

/** Stable history filenames share one identity digest across live and legacy authority. */
internal class CodeModeHistoryPaths(root: Path) {
    val directory: Path = root.resolve("history")

    fun journal(key: String): Path = directory.resolve(InputDigest.hex(key) + ".jsonl")

    fun legacy(key: String): Path = directory.resolve(InputDigest.hex(key) + ".json")
}
