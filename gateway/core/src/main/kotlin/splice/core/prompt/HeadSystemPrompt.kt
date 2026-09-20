// NEW: v0.4.0 (operator ask 2026-09-15) — a head's standing system prompt: text inline under
// [heads.KEY] or read from a file, riding on EVERY turn at that dialect's system seam.
// DERIVE, NOT RE-AUTHOR: this mirrors V4-07's compaction resolver one-to-one
// (core/compaction/CompactionInstructions.kt) — the same inline-or-file idiom, the same
// "both present is a load-time error, never silent precedence" rule, and the same inspectable
// effective text + source. The one deliberate divergence is the error path: a compaction file that
// goes unreadable disables its rule (the turn still runs), while an unreadable system prompt file
// is a CONFIG ERROR AT LOAD — a standing instruction must never be silently absent from the wire.
package splice.core.prompt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * How a head's standing prompt takes the wire.
 *
 * [APPEND] is the default and is purely additive: the client's own system field rides through
 * byte-identically and the prompt is placed beside it, so the request prefix keeps every existing
 * cache_control breakpoint and the prompt cache warms from turn two onward.
 *
 * [REPLACE] substitutes the client's whole system field. THE CONSEQUENCE AN OPERATOR MUST KNOW:
 * Claude Code ships its ENTIRE operating instruction set in that field, so replacing it strips the
 * harness instructions and the head then behaves like a bare model with tools attached. The cache
 * cost is a prefix change ONCE per session rather than per turn, so the cache still warms from turn
 * two — the invariant is unaffected.
 *
 * [STRIP] (V4-170) keeps the client's field and DELETES paragraphs from it: the layer's text is a
 * pattern list ([ParagraphStrip]), and every paragraph of the client's own system text that a
 * pattern matches is removed at the dialect seam. Every other paragraph, block and cache_control
 * breakpoint rides through byte-identically, so the dynamic blocks a session needs survive and the
 * cache still warms from turn two. This is the mode for "Claude Code's prompt without the hedges".
 */
@Serializable
public enum class SystemPromptMode(public val wire: String) {
    @SerialName("append")
    APPEND("append"),

    @SerialName("replace")
    REPLACE("replace"),

    @SerialName("strip")
    STRIP("strip"),
}

/** Reads one prompt file named by `system_prompt_file`. */
public fun interface SystemPromptFileRead {
    public operator fun invoke(path: Path): String
}

/** The effective prompt, the seam it takes, and where its text came from — read by TurnMeta and by
 *  the operator surfaces, the way [splice.core.compaction.EffectiveCompactionInstructions] exposes
 *  its own text and source. */
public data class EffectiveSystemPrompt(
    val text: String,
    val mode: SystemPromptMode,
    val source: String,
)

/**
 * Resolves ONE head's standing system prompt at load.
 *
 * Absent — or the empty string, which is the operator's explicit "no prompt" — resolves to null, so
 * a head that configures nothing sends today's bytes exactly. A missing or unreadable
 * `system_prompt_file` throws here, at load, rather than resolving to a silently empty prompt.
 */
public class HeadSystemPrompt(
    text: String? = null,
    file: String? = null,
    private val mode: SystemPromptMode = SystemPromptMode.APPEND,
    private val configDir: Path = Paths.get(System.getProperty("user.home"), ".config", "splice"),
    private val readFile: SystemPromptFileRead = SystemPromptFileRead { Files.readString(it) },
    private val source: String = "head",
) {
    init {
        require(text == null || file == null) {
            "$source cannot set both system_prompt and system_prompt_file"
        }
    }

    /** Provenance, carrying the mode: a `replace` head and an `append` head read very differently in
     *  a log line, and both differ from the same head with no prompt at all. */
    private val origin: String = if (file == null) {
        "$source ${mode.wire}"
    } else {
        "$source ${mode.wire} file:${resolvePath(file)}"
    }

    private val prompt: String? = when {
        text != null -> text
        file == null -> null
        else -> read(file)
    }

    init {
        // V4-170: a strip layer's text is a pattern list; a bad regex is a load error, never a layer
        // that silently strips nothing.
        if (mode == SystemPromptMode.STRIP) prompt?.takeIf(String::isNotEmpty)?.let { ParagraphStrip(it, source) }
    }

    /** The prompt this head places on every turn, or null when it configures none. */
    public fun resolve(): EffectiveSystemPrompt? =
        prompt?.takeIf(String::isNotEmpty)?.let { EffectiveSystemPrompt(it, mode, origin) }

    private fun read(file: String): String {
        val path = resolvePath(file)
        return Cancellables.runCatchingCancellable { readFile(path) }.getOrElse { failure ->
            throw IllegalArgumentException(
                "$source system_prompt_file is unreadable: $path (${SafeFailureText.render(failure)})",
                failure,
            )
        }
    }

    /** Mirrors [splice.core.compaction.CompactionInstructions]'s rule for its `file =`: `~/` is the
     *  home directory and a relative path is under the topology's directory. */
    private fun resolvePath(raw: String): Path {
        val expanded = if (raw.startsWith("~/")) {
            System.getProperty("user.home") + raw.substring(1)
        } else {
            raw
        }
        val path = Paths.get(expanded)
        return if (path.isAbsolute) path.normalize() else configDir.resolve(path).normalize()
    }
}
