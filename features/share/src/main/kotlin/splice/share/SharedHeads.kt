// NEW: LAN share (docs/specs/lan-share.md). The heads this machine offers its other machines, as the share listener
// reads them at each request. app implements it over its running heads; the listener never sees ManagedHead.
package splice.share

import splice.launch.LaunchSpec

/** One head this machine offers: every head that holds its own credentials. */
public interface SharedHead {
    /** Its topology key. */
    public val key: String

    /** The command the person runs it as here, which its address on the LAN names. */
    public val command: String

    /** Its port on this machine's loopback, where the listener relays a turn. */
    public val port: Int

    public fun running(): Boolean

    /** Its launch as a session here gets it now, live windows and all; null for a head that launches nothing. */
    public fun launch(): LaunchSpec?

    /** Its logins, by label: what an address's `auth=` may name. */
    public fun logins(): List<String>

    /** Pins [session] to [login] through the head's own pool, as the Accounts page's Pin does; false when the head
     *  has no login of that label. */
    public fun pin(login: String, session: String): Boolean

    /** The most a turn's body may hold, as this head reads it itself. */
    public fun maxRequestBytes(): Int

    /** How long this head waits for a turn's body to arrive whole, as it reads it itself. */
    public fun requestReadTimeoutMs(): Long
}

/** What this machine offers, read at each request. */
public interface SharedHeads {
    /** This machine's name, as its other machines are told it. */
    public val machine: String

    public fun offered(): List<SharedHead>
}
