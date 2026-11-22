// NEW: the service every mode implements (modes/README.md, "The contract"). splice finds each mode's class through
// java.util.ServiceLoader, from the META-INF/services entry the splice.mode plugin writes from the manifest's handler.
package splice.modes.api

/** One mode's code: it answers a call of one of its tools, may give the content of a resource its manifest declares,
 *  and may answer management routes under `/api/`. A mode
 *  class has a public no-argument constructor, so the ServiceLoader can make it. */
public interface Mode {
    /** The answer to [call], a text or an error the model reads. A handler that throws is answered for it:
     *  "mode <name> failed: <exception class>", as an error. */
    public fun answer(call: ModeCall): ModeAnswer

    /** The content of the resource [uri], for one the manifest declares with no `file`; null when this mode has none
     *  to give for it. Only an mcp mode is asked, and only for a resource its manifest lists. */
    public fun resource(uri: String): String? = null

    /** The management routes this mode answers, each a method and an exact path under `/api/`. */
    public val routes: List<ModeRoute> get() = emptyList()
}
