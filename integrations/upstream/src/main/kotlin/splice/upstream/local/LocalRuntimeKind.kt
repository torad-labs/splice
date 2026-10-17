// NEW: names of user-managed local runtimes the probe can detect. Moved out of the chat dialect
// so ollama/lmstudio detection is not a dialect vendor fact (and, V4-103, out of :app into the
// provider contract the probe now lives in).
package splice.upstream.local

public enum class LocalRuntimeKind(public val label: String) {
    OLLAMA("Ollama"),
    LM_STUDIO("LM Studio"),
    VLLM("vLLM"),
    OPENAI_COMPATIBLE("OpenAI-compatible"),
}
