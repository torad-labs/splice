// NEW: names of user-managed local runtimes the probe can detect. Moved out of the chat dialect
// so ollama/lmstudio detection is not a dialect vendor fact.
package splice.app.provider.local

public enum class LocalRuntimeKind(public val label: String) {
    OLLAMA("Ollama"),
    LM_STUDIO("LM Studio"),
    VLLM("vLLM"),
    OPENAI_COMPATIBLE("OpenAI-compatible"),
}
