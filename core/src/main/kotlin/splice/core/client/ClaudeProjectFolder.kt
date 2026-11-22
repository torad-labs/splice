// NEW: Nov 22, 2026 — how Claude names a project's folder under its `<config>/projects/` tree, spelled once for every
// writer and reader of that tree. It used to live twice: the antigravity transcript writer named its file's folder from
// Desktop 6.28899.2 (its `sf`) in full, while the sessions' projects read beside head memory carried only the slug half,
// disagreeing on any path past 200 characters.
package splice.core.client

import kotlin.math.abs

/** The name of the folder Claude Code keeps a project's files in, under its config folder's `projects` tree, spelled
 *  from [path]. Whoever writes into the tree — a Gemini session's Claude-shaped transcript — and whoever reads beside
 *  what was written there — each head's client memory — answer through this one function, so both spell alike. */
public object ClaudeProjectFolder {
    /** [path] as Desktop 6.28899.2 spells it (its `sf`): every character but a letter or digit made `-`, and past 200
     *  characters cut there and told apart by a hash of the WHOLE path in base 36. The hash folds the original [path]
     *  before any replacement; a silent change to either half strands history where the client cannot reopen it, so
     *  [ClaudeProjectFolderTest] holds exact names for every branch. */
    public fun of(path: String): String {
        val name = NOT_PROJECT_CHARS.replace(path, "-")
        if (name.length <= MAX_PROJECT_FOLDER_CHARS) return name
        // abs is taken on the widened value, not the int: Int.MIN_VALUE has no positive int self.
        return name.take(MAX_PROJECT_FOLDER_CHARS) + "-" + abs(path.hashCode().toLong()).toString(PROJECT_HASH_RADIX)
    }

    // why: Claude Desktop 6.28899.2 (`sf`) truncates project folder slugs at 200 characters before appending the hash.
    private const val MAX_PROJECT_FOLDER_CHARS = 200

    // why: Claude Desktop 6.28899.2 (`sf`) encodes the collision-avoiding path hash in base 36.
    private const val PROJECT_HASH_RADIX = 36

    private val NOT_PROJECT_CHARS = Regex("[^a-zA-Z0-9]")
}
