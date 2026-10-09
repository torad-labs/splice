// NEW: what the native placement found for one replay anchor, kept as a single log-safe value.
package splice.provider.codex.state.diagnostics

/** Only fixed categories and a flag: where the witness came from, the kind of item it names, and whether it
 *  resolved in the history. */
internal data class CodeModeNativeWitness(val source: String, val kind: String, val resolved: Boolean)
