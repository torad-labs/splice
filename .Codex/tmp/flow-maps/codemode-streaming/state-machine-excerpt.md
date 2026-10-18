# State-machine trace: code mode and GPT streaming

Seed: a Claude Code streaming request routed to GPT/Codex with code mode enabled. Source inspection only; no source/test edits, tests, or benchmark runs. The map has 12 main states. Other mapper outputs were not read.

1. **Round posted → streaming visible output.** The code-mode interceptor posts through the ordinary transport. Responses text deltas open/write text directly in `ResponsesItemFold.kt:148–164`; reasoning has its own live fold. A fold-eligible turn intentionally wraps text/tools in `BufferingWireSink` until its terminal round is known (`FoldRunner.kt:48–66`). This applies only when the model has a fold policy, not every GPT turn.
2. **Exec admitted → source and worker stream together.** `ResponsesExecProgress.kt:24–47` forwards exec start and input deltas to the runtime observer. The script is intentionally hidden from the client under the October 12 product decision recorded in that file. `CodeModeSourceCapture.kt:63–85` admits a durable record and appends a monotonic source prefix. The first request owns the client attachment; the response reader has a separate lifecycle.
3. **Streaming source → executable callback.** `CodeModeStatementParser.kt:115–150` certifies a statement boundary only after a following token rules out grammar continuation. `StreamingWorkerSession.kt:41–82` checks dependencies/intrinsics and either executes or defers the statement. Function hoisting, async continuations, unknown calls, and some native globals intentionally wait for the complete source. A normal model pause or such a semantic deferral is not evidence of a buffering fault.
4. **Executable callback → durable client call → parked source.** The runtime yields `CodeModeStep.Calls`. `CodexCodeModeCallIssuer.kt:83–120` validates and records newly issued calls; `CodexCodeModeMachine.kt:109–155` persists issuance before writing ordinary tool_use blocks. `CodeModeClientStepSink.kt:13–16` detaches the response reader before callback publication. The client runs permission-controlled tools while source generation can continue independently. Detached commentary writes are bounded and drain when the next request attaches (`CodeModeSinkWrites.kt:25–49`); a burst at this attachment boundary is intentional.
5. **Item done → certification wait.** `CodeModeSourceCapture.kt:49–57` seals source on item completion. `CodeModeSourceBuffer.kt:57–68` freezes unread source and blocks a callback selected at that boundary until the full response certifies its identity/prefix/completion. This prevents dispatch from an incomplete or corrupted terminal; the existing buffered-certification tests explicitly pin it.
6. **Response terminal → transport draining.** The reducer records `response.completed`/`response.done`/`response.incomplete` (`ResponsesEventReducer.kt:65–85`), but the translator's collect loop continues until its input Flow ends (`ResponsesStreamTranslator.kt:113–170`). The SSE reader ends on EOF, while the WebSocket Flow explicitly stops on terminal (`WsRoundStream.kt:30–45`).
   - **GAP 1, contributing to pauses:** if an SSE connection remains open after a valid response terminal, the source cannot certify, pending callback waits cannot release, and hidden continuation cannot post until EOF or cancellation. Code-mode certification occurs only when `post.into` returns an outcome (`CodeModeLiveRound.kt:149–160, 196–222`; `CodeModeSourceCapture.kt:120–145`). A terminal should make the round outcome available immediately. The source proves this conditional mechanism; its share of the user's live symptom remains unmeasured.
7. **Client results → same worker resumes.** `CodexCodeModeResume.kt:98–171` accepts matching visible callback results, translates them to runtime ids, and advances the retained cell. `JvmCodeModeCell.kt:102–146` partitions result frames and preserves unresolved siblings. New dependent calls may yield another client step. A step with only outstanding results currently returns a local `CODE_MODE_PROTOCOL` failure through `CodexCodeModeIncrementalOutcomes.kt:12–33`; that is established behavior, not asserted here as a newly discovered defect.
8. **Script complete → canonical history → next model round.** `CodexCodeModeDriver.kt:102–126` waits for source outcome/usage, canonicalizes the outer custom call plus its output, and posts a hidden next round. Final model text streams normally unless a fold wrapper intentionally holds it.
9. **Failure/interruption paths.** Torn/refused/changed/incomplete source and dead readers fail their source and close the cell. Only a typed startup failure proven to precede dispatch can retry source (`CodeModeRuntimeStarter.kt:81–115`). Lost cells are not rerun: on a later client request, interruption output preserves accepted results and unresolved calls, and the model continues (`CodexCodeModeResume.kt:78–95`). Steering stops a live script; system-only additions wait if every call is answered (`CodexCodeModeResume.kt:230–247`). The outer turn pipeline owns the honest terminal.

## Concrete code-mode correctness gap

**GAP 2, root cause for a reproducible input-recovery failure:** the worker consumes a callback id before the callback is accepted. In `CodeModeWorker.kt:192–199`, the launcher validates the original argument is an object and that `JSON.stringify` returned a string, then increments `sequence`, inserts a pending entry, and invokes `host.call`. `WorkerBridge.parseCall` at `CodeModeWorker.kt:158–171` additionally requires decoded arguments to be a JSON object. For an object whose `toJSON` returns null, that host check rejects after id 1 has already been reserved. The catch rejects the JavaScript promise but does not undo the id/pending reservation. A later valid call is id 2; `CodeModeFrames.kt:162–166` expects the first visible callback to be id 1 and faults the cell.

Source-grounded reproducer (not executed):

```js
try {
  await tools.Read({toJSON() { return null; }});
} catch {}
text(await tools.Read({path: 'good'}));
```

The first bad argument should reject locally and leave the script able to call a valid tool. Validate the decoded JSON shape before reserving an id, and ensure a host-side rejection cannot strand an accepted-id gap. Existing runtime tests cover batching/id continuity and the 33rd-call cap; searches found no `toJSON` recovery case.

## Structural tradeoff to review, not mislabeled as a transport bug

Splice can execute a valid early tool statement before it has received/parses the complete program. For example a prefix `await tools.Edit({...});\ntext(` can issue Edit before a malformed suffix is known. `CodeModeStatementParser.terminalError` parses the original full source only at completion (`CodeModeStatementParser.kt:66–68`), and `StreamingWorkerSession.run` permits a ready statement before completion (`StreamingWorkerSession.kt:65–80`). A later syntax error therefore can be reported after that earlier tool's effects occurred. Full-source execution rejects such a program before invoking tools. This is a direct consequence of the streaming policy, not an accidental wait. If atomic whole-program syntax validation is required for mutating callbacks, that guarantee needs an explicit execution policy; the current source does not provide it.

## What this trace does not establish

- It does not establish that GPT output is generally buffered by the socket writer: `ImmediateSseWriter.kt:67–69` writes and flushes every frame.
- It does not establish that the pacer creates long freezes: `DeltaPacer` deliberately spreads bursts over approximately one second and preserves order; source-level timing here does not prove its live behavior.
- It does not claim that intentional hidden script bytes, dependency deferral, source certification, or detached-step buffering are errors.
- It does not claim frequency for either found mechanism without a runtime trace/reproduction.
