#!/usr/bin/env bun
/** pretooluse lifecycle entry — dispatches to modules/pretooluse/*.ts.
 *
 *  FAIL-OPEN AT THIS LAYER, deliberately and visibly. Python wrapped the dispatch in
 *  `except Exception: pass`, which is what made every hook silently disable itself rather than
 *  wedge a seat — and that is the right call for THIS layer, because an entrypoint that threw
 *  would stop every tool call in the repo. The place a crash must BLOCK is decided per module by
 *  its FAIL_CLOSED flag (runner.ts honours it), not here: a security module's crash becomes a
 *  block, a style module's does not, and this catch is the outermost net beneath both.
 */
import { dispatch } from "./runner";

try {
  await dispatch("pretooluse");
} catch {
  // see the header: this layer fails open on purpose
}
