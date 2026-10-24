package splice.dialect.responses.reasoning

/** Presentation-only filtering. Stored reasoning items and their summaries are never rewritten. */
internal object ReadableReasoning {
    fun hasText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == EMPTY_BODY) return false
        if (!trimmed.startsWith("**")) return true
        val close = trimmed.indexOf("**", startIndex = 2)
        return close < 0 || trimmed.substring(close + 2).trim() != EMPTY_BODY
    }

    /** Hold only a leading blank/comment candidate; a real body releases every model byte unchanged. */
    fun mayBeEmpty(text: String): Boolean = EMPTY_BODY.startsWith(text.trim())

    /** A completed, trimmed summary body is empty only when absent or exactly the model's empty-body marker. */
    fun isEmptyBody(text: String): Boolean = text.isEmpty() || text == EMPTY_BODY
}

private const val EMPTY_BODY = "<!-- -->"
