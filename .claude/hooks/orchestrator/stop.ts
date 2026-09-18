#!/usr/bin/env bun
/** stop lifecycle entry — dispatches to modules/stop/*.ts.
 *
 *  Same FAIL-OPEN-AT-THIS-LAYER policy as pretooluse.ts, and for the same reason: an entrypoint
 *  that threw would stop every tool call. The block decision belongs to each module's
 *  FAIL_CLOSED flag, which runner.ts honours. This lifecycle is Stop / SubagentStop.
 */
import { dispatch } from "./runner";

try {
  await dispatch("stop");
} catch {
  // see the header: this layer fails open on purpose
}
