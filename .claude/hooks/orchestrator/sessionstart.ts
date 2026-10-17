#!/usr/bin/env bun
/** sessionstart lifecycle entry — dispatches to modules/sessionstart/*.ts.
 *
 *  Same FAIL-OPEN-AT-THIS-LAYER policy as pretooluse.ts, and for the same reason: an entrypoint
 *  that threw would stop every tool call. The block decision belongs to each module's
 *  FAIL_CLOSED flag, which runner.ts honours. This lifecycle is SessionStart.
 */
import { dispatch } from "./runner";

try {
  await dispatch("sessionstart");
} catch {
  // see the header: this layer fails open on purpose
}
