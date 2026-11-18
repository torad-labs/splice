// NEW: desktop-door (docs/specs/desktop-door.md, decision 7). The door a turn came through, as the listener in front
// of a head names it to that head: the head records it on the turn's row, and every other turn carries none.
// door-default (docs/specs/door-default.md, decision 3): the door has two callers, Claude Desktop's Code tab and the
// terminal's `claude`, and a row says which of them sent its turn. Decision 9: `claude` on its head hands another
// head's model to that head, and that head's row says it came through `claude`.
package splice.core.turn

/** The header a listener that relays a turn to a head names the turn's door with. The relay sets it itself and never
 *  passes on a client's own. */
public const val TURN_DOOR_HEADER: String = "x-splice-door"

/** Claude Desktop's Code tab, at the door under `/desktop`. */
public const val DESKTOP_DOOR: String = "desktop"

/** The terminal's `claude`, at the same door with a key of its own. */
public const val TERMINAL_DOOR: String = "terminal"

/** The terminal's `claude` on its head, which hands a model of another head to that head (door-default, decision 9). */
public const val CLAUDE_DOOR: String = "claude"

/** Every name a row's door can carry. */
public val turnDoors: Set<String> = setOf(DESKTOP_DOOR, TERMINAL_DOOR, CLAUDE_DOOR)
