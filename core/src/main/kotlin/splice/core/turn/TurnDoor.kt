// NEW: desktop-door (docs/specs/desktop-door.md, decision 7). The door a turn came through, as the listener in front
// of a head names it to that head: the head records it on the turn's row, and every other turn carries none.
package splice.core.turn

/** The header a listener that relays a turn to a head names the turn's door with. The relay sets it itself and never
 *  passes on a client's own. */
public const val TURN_DOOR_HEADER: String = "x-splice-door"

/** The one door there is: Claude Desktop's Code tab, answered under `/desktop`. */
public const val DESKTOP_DOOR: String = "desktop"
