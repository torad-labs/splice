// NEW: the one spelling of a head's CLAUDE_CONFIG_DIR (moved here from LaunchSpecFactory so `splice status` and
// `splice doctor` can resolve the same folder without depending on the app). The wrapped Claude Code's per-head
// CLAUDE_CONFIG_DIR default is this prefix plus the head key; the prefix is one const, and keeping the join in ONE
// member is what stops a second spelling appearing when a second caller needs it. V4-276: an object, because
// `splice login <claude-head> --label` is a caller that has no factory.
package splice.core.config

import java.nio.file.Path
import java.nio.file.Paths

/** The wrapped Claude Code's per-head CLAUDE_CONFIG_DIR default is this prefix plus the head key. */
internal const val HEAD_CONFIG_DIR_PREFIX: String = "~/.claude-"

/** The settings file at the top of a Claude Code folder, which splice writes as a head's own and reads back. */
public const val CLAUDE_FOLDER_SETTINGS: String = "settings.json"

/** One head's CLAUDE_CONFIG_DIR: the declared path, else this tree's `~/.claude-<key>` default. */
public object HeadConfigDirs {
    public fun of(headKey: String, declared: String?): Path = of(headKey, declared, UserHome.dir())

    public fun of(headKey: String, declared: String?, home: Path): Path {
        val selected = declared ?: (HEAD_CONFIG_DIR_PREFIX + headKey)
        return Paths.get(if (selected.startsWith("~/")) home.toString() + selected.substring(1) else selected)
    }
}
