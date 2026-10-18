# Implemented fixes

The user requested all four fixes identified in the original review. The source maps remain a record
of the code at `ebd66db6a`; the implementation changes are described here.

1. **Rejected callback arguments no longer poison the next accepted call.** `CodeModeWorker` validates
   the serialized, decoded object before reserving an ID. The sequence advances only after the host
   accepts the call; rejection removes its pending promise. The receiver's strict consecutive-ID check
   remains in force. A real-worker regression exercises normal and streaming execution, catches five
   invalid `toJSON()` results, then successfully completes a valid tool call.
2. **Responses completion ends the read without awaiting HTTP EOF.** `ResponsesStreamTranslator` stops
   after processing the final response and retains its existing cleanup, certification, usage, and
   failure classification. Failure events still support the existing read-through behavior. A regression
   supplies a completed response followed by a body that stays open forever. Cancellation fixtures run
   their late cancellation during unwinding, preserving their cancellation and accounting assertions
   when the collector stops early. The WebSocket round recognizes a consumed terminal followed by a
   collector's early stop as reusable while still rejecting actual job cancellation and unread frames;
   a regression verifies a second round reuses that connection.
3. **Large visible deltas participate in pacing.** The shared `DeltaPacer` lazily splits text and
   thinking content longer than 256 UTF-16 units, using its existing tick, budget, and maximum hold
   window. Slices preserve model content, code-point boundaries, block indices, extra JSON fields, and
   frame order. The number of slices per original frame is capped at 256. A forced drain writes only
   the unread suffix, preventing duplicate content after partial delivery. Regressions cover text and
   thinking, Unicode and escaped characters, terminal ordering, partial drain, and an inactive pacer.
4. **Cell timing shares the cell's lifetime.** The independent `started` map is removed. The first
   advance timestamp is an in-memory field of `CodeModeRecord`, cleared on completion, loss, parking,
   and head stop; record expiry therefore cannot strand an entry in a separate map. Existing
   pre-advance failure regressions now check that the record's timestamp is cleared.

## Verification

- `bun tools/gate rules`: passed, including all 69 rule fixtures, routing, configuration guards, and
  single-source checks.
- `./gradlew :app:shadowJar oracleReplay --console=plain`: `BUILD SUCCESSFUL`; a fresh application jar
  replayed all 11 scenarios with byte-exact oracle matches.
- All five affected Kotlin module checks: `BUILD SUCCESSFUL`. Their 2,786 tests passed with no failures,
  errors, or skips: code-mode 150, Responses 507, Codex provider 726, turns 1,183, architecture 220.
  Module lint, static analysis, and coverage checks also passed. The command was:

  ```sh
  ./gradlew :integrations-codemode:check :integrations-dialects-openai-responses:check :integrations-providers-codex:check :features-turns:check :quality-architecture:check --console=plain
  ```

## Scope and limits

The installed configuration already selects WebSockets first. Its defaults, reasoning replay/mirror,
hidden source, statement readiness, folding, and early-execution policy are unchanged. No configuration
was changed, and the running daemon was neither installed nor restarted.

These changes remove the identified local defects and smooth large frames after content arrives.
The original passive WebSocket sample also contained seconds of silence before pacing. This work does
not establish that all reported pauses have a local cause or eliminate upstream generation delays.
The complete release gate was not run; this is a source fix with targeted module and wire validation.
