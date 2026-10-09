// NEW: which model a chat request names on the wire, and which one the client asked for (default-parameter
// width flip, 2026-10-09). Two strings of one type that a call site could swap without the compiler noticing,
// travelling together through every build; the pair gets one name so a call says which is which.
package splice.dialect.chat

/** [upstream] is the model id sent to the provider; [original] is the id the client asked for, kept for the
 *  response side. They are equal when no routing rewrote the id. */
public data class ChatRoute(public val upstream: String, public val original: String = upstream)
