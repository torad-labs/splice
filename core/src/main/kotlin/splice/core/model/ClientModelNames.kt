// NEW: 2026-11-20 — the model ids Claude's own clients keep, and the readable name splice gives a selector they would
// drop. Claude Desktop (5.11492) and the Claude Code it starts in gateway mode (2.1.325, `ODe`/`Vo` and `sP`/`op`) keep
// a model id only when it names Claude (claude, anthropic or a tier) and no other lab; any id with gpt, grok, kimi,
// codex, deepseek, glm and the like in it is dropped before any other field is read, and a session or subagent on it
// falls back to the default model, saying nothing. `claude-grok--grok-4.7` is such an id. Mirror the client, never
// improve on it: the list below is the client's, word for word.
package splice.core.model

/** Whether Claude's clients keep a model id, and the readable name a selector they would drop is given instead. */
public object ClientModelNames {
    /** Claude Desktop's and Claude Code's own list of other labs' names, matched anywhere in a lowercased id. */
    private val otherLab = Regex(
        "ark-code|astron|command-r|deepseek|doubao|gemini|gemma|glm|gpt|grok|hermes|hy3|kimi|lfm|\\bling\\b|llama|" +
            "longcat|mimo|minimax|mistral|mixtral|moonshot|nemotron|openai|phi-|qianfan|qwen|tc-code|\\bunic\\b|yi-|" +
            "stepfun|step-3|seed-|bytedance|hunyuan|granite|amazon\\.nova|nova-|devstral|ministral|ernie|codex|arcee|" +
            "trinity|abab|phi\\d|\\bk2\\.|\\bm2\\.|jamba|arctic|solar|mercury|zamba|kat-coder|\\bds-|dpsk",
    )
    private val tierAlias = Regex("^(sonnet|opus|haiku|fable|mythos)(-[\\d.]+)?$")
    private val claudeWords = listOf("claude", "sonnet", "opus", "haiku", "fable", "mythos", "anthropic")

    /** How a lab's name reads in a readable name; a name not here has a `_` after its first letter. */
    private val neutral = mapOf(
        "gpt" to "oai", "openai" to "oai", "codex" to "oai",
        "grok" to "xai",
        "kimi" to "moon", "moonshot" to "moon",
        "deepseek" to "dseek", "dpsk" to "dseek",
        "glm" to "zai",
        "gemini" to "gem", "gemma" to "gem",
        "qwen" to "qw", "llama" to "lla", "minimax" to "mmx", "nemotron" to "nemo",
        "mistral" to "mist", "mixtral" to "mixt", "devstral" to "devst", "ministral" to "minist",
    )

    /** True when Claude Desktop and its Claude Code keep [id] as a model id. */
    public fun kept(id: String): Boolean {
        val lower = id.lowercase()
        return !otherLab.containsMatchIn(lower) && (tierAlias.matches(lower) || claudeWords.any { it in lower })
    }

    /** The prefix a head's readable names start with: its selectors' [prefix] with each lab's name put another way. */
    public fun prefixOf(prefix: String): String = neutralized(prefix)

    /** [selector], of the head whose selectors start [prefix], as Claude's clients keep it: itself when they do, else
     *  [prefixOf] [prefix] and the model with each lab's name put another way. With [short], a model that starts with
     *  its lab's name (`grok-4.7` on `claude-grok--`) leaves it out when the prefix already said it:
     *  `claude-xai--4.7`. */
    public fun readable(selector: String, prefix: String, short: Boolean = true): String {
        if (kept(selector)) return selector
        val head = prefixOf(prefix)
        val model = selector.removePrefix(prefix)
        val lead = if (short && head != prefix) leadingLab(model) else null
        return head + neutralized(lead?.let { model.substring(it.length + 1) } ?: model)
    }

    /** The lab's name [model] starts with, followed by `-`; null when it starts with none. */
    private fun leadingLab(model: String): String? =
        otherLab.find(model.lowercase())?.value?.takeIf { model.lowercase().startsWith("$it-") }

    private fun neutralized(text: String): String {
        val lower = text.lowercase()
        val out = StringBuilder()
        var at = 0
        otherLab.findAll(lower).forEach { match ->
            out.append(text, at, match.range.first)
            val word = text.substring(match.range)
            out.append(neutral[match.value] ?: (word.take(1) + "_" + word.drop(1)))
            at = match.range.last + 1
        }
        return out.append(text.substring(at)).toString()
    }
}
