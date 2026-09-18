#!/usr/bin/env bun
/** posttooluse lifecycle entry — dispatches to modules/posttooluse/*.ts.
 *
 *  Same FAIL-OPEN-AT-THIS-LAYER policy as pretooluse.ts, and for the same reason: an entrypoint
 *  that threw would stop every tool call. The block decision belongs to each module's
 *  FAIL_CLOSED flag, which runner.ts honours. This lifecycle is PostToolUse.
 */
import { dispatch } from "./runner";

try {
  await dispatch("posttooluse");
} catch {
  // see the header: this layer fails open on purpose
}
