// The playground's wire, both directions, named after the daemon code that reads and writes it:
//   POST /api/playground   PlaygroundBody -> PlaygroundWire   (V4-133, PlaygroundRoute.run)
//
// The route carries the pair VERBATIM (PlaygroundRoute.kt:99-102 serializes whatever the probe
// returned), so the shape of each side is the one probe's: UpstreamPlaygroundProbe.kt:132-141. The
// two bodies inside are the upstream vendor's and are left `unknown` on purpose: the console shows
// them raw and reads no field of either, and typing a vendor's reply would be inventing a contract
// the daemon itself refuses to interpret.

/** What the route parses (PlaygroundRoute.kt:70-71). Both fields are required in practice: a blank
 *  prompt and an unknown head are each a 400 naming the problem. */
export interface PlaygroundBody {
  head: string;
  prompt: string;
}

/** The request the daemon SENT upstream, with every auth header value redacted by the daemon
 *  (HeaderRedaction.REDACTED) before it left the process. */
export interface PlaygroundSent {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

/** What the upstream answered: its HTTP status and its body, parsed as JSON where it was JSON and
 *  `{"raw": "<text>"}` where it was not (an event stream, an HTML error page). */
export interface PlaygroundReceived {
  status: number;
  body: unknown;
}

/** POST /api/playground's 200 answer. Never recorded by the daemon, and never stored by the console:
 *  the page holds it in one reducer's state and drops it at the next run. */
export interface PlaygroundWire {
  request: PlaygroundSent;
  response: PlaygroundReceived;
}
