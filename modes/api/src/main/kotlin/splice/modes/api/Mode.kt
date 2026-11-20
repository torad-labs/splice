// NEW: the service every mode implements (modes/README.md, "The contract"). splice finds each mode's class through
// java.util.ServiceLoader, from the META-INF/services entry the splice.mode plugin writes from the manifest's handler.
package splice.modes.api

/** One mode's code: it answers a call of one of its tools, and may answer management routes under `/api/`. A mode
 *  class has a public no-argument constructor, so the ServiceLoader can make it. */
public interface Mode {
    /** The answer to [call], a text or an error the model reads. A handler that throws is answered for it:
     *  "mode <name> failed: <exception class>", as an error. */
    public fun answer(call: ModeCall): ModeAnswer

    /** The management routes this mode answers, each a method and an exact path under `/api/`. */
    public val routes: List<ModeRoute> get() = emptyList()
}
