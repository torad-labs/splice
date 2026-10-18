# GPT code-mode failures and streaming pauses

---

## 1. Dependency graph

### Entities

**features/turns**

- `BufferingWireSink` (class) [] — `features/turns/src/main/kotlin/splice/head/wire/BufferingWireSink.kt:14-69`
- `ClientChannel` (class) [per-turn] — `features/turns/src/main/kotlin/splice/head/wire/ClientChannel.kt:105-383`
- `DeltaPacer` (state-container) [one streaming client turn] — `features/turns/src/main/kotlin/splice/head/wire/DeltaPacer.kt:51-142`
- `FoldRunner` (class) [] — `features/turns/src/main/kotlin/splice/head/round/FoldRunner.kt:24-115`
- `RoundStrategy` (class) [per-turn] — `features/turns/src/main/kotlin/splice/head/round/RoundStrategy.kt:38-122`
- `SseEmitter` (class) [per-turn] — `features/turns/src/main/kotlin/splice/head/wire/SseEmitter.kt:53-305`
- `SseRoundConsume` (class) [] — `features/turns/src/main/kotlin/splice/head/transport/SseRoundConsume.kt:22-144`
- `SseRoundDriver` (class) [] — `features/turns/src/main/kotlin/splice/head/transport/SseRoundDriver.kt:24-154`

**integrations/codemode**

- `JvmCodeModeRuntime` (class) [] — `integrations/codemode/src/main/kotlin/splice/codemode/JvmCodeModeRuntime.kt:61-185`

**integrations/dialects/openai-responses**

- `ResponsesEventReducer` (class) [] — `integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesEventReducer.kt:24-156`
- `ResponsesExecProgress` (class) [] — `integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesExecProgress.kt:20-57`
- `ResponsesStreamTranslator` (class) [per-turn] — `integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesStreamTranslator.kt:56-61`
- `WsRoundStream` (class) [] — `integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/websocket/WsRoundStream.kt:19-74`

**integrations/providers/codex**

- `CodeModeLiveRound` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeLiveRound.kt:31-327`
- `CodeModeRoundInterceptor` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeUpstreamPost.kt:28-65`
- `CodeModeSourceBuffer` (state-container) [CodeModeLiveRound source] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeSourceBuffer.kt:21-162`
- `CodeModeSourceCapture` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeSourceCapture.kt:14-147`
- `CodeModeStreamingCell` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeStreamingCell.kt:15-69`
- `CodeModeStreams` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeStreams.kt:84-219`
- `CodeModeSwitchingSink` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeSwitchingSink.kt:27-48`
- `CodexCodeModeDriver` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeDriver.kt:33-314`
- `CodexCodeModeMachine` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeMachine.kt:48-284`
- `CodexCodeModeResume` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeResume.kt:23-257`
- `CodexCodeModeTurnBuilder` (class) [] — `integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeTurnBuilder.kt:29-180`

**integrations/upstream**

- `SseReader` (class) [] — `integrations/upstream/src/main/kotlin/splice/upstream/sse/SseReader.kt:34-35`

### Hyperedges (n-ary relations)

**relation** (SseFrameWriter/FrameWrite connection; frames travel through mutex-protected timedClientWrite.) [dep_channel]
  - `SseEmitter` _caller_ [tail]
  - `ClientChannel` _callee_ [head]

**relation** (After record completion, CodexCodeModeResume.kt:125 sends canonical script output upstream through finishGenerated.) [dep_continue]
  - `CodexCodeModeResume` _caller_ [tail]
  - `CodexCodeModeDriver` _callee_ [head]

**relation** (ResponsesStreamTranslator.kt:107-110,133 reduces each event into buffers and sink operations.) [dep_event_fold]
  - `ResponsesStreamTranslator` _caller_ [tail]
  - `ResponsesEventReducer` _callee_ [head]

**relation** (CodexCodeModeDriver.kt:93-99 and startRuntime route to machine.advance after worker placement.) [dep_first_advance]
  - `CodexCodeModeDriver` _caller_ [tail]
  - `CodexCodeModeMachine` _callee_ [head]

**gap** (When SSE terminal is valid but body EOF is delayed, translator still collects. CustomToolSource.Terminal is emitted only in collector finally; finishRound and source.complete wait too. This delays final message stop or callbacks that meet source seal, including SSE fallback. ResponsesStreamTranslator.kt:114-133,157-169; ResponsesEventReducer.kt:67-84; CodeModeSourceBuffer.kt:57-68. Existing ResponsesTerminalDecision.kt:27-30 explicitly documents EOF suspension after response.completed. WebSocket path is unaffected because its Flow ends at terminal.) [dep_gap_sse_terminal_eof]
  - `ResponsesEventReducer` _terminal-already-parsed_
  - `ResponsesStreamTranslator` _waiting-collector_
  - `SseReader` _waiting-http-body-eof_
  - `CodeModeSourceBuffer` _sealed-source-awaiting-certification_
  - `CodeModeStreamingCell` _blocked-client-step_

**client-step-ownership** (CodeModeStreamingCell.kt:31 detaches before tool callback publication. CodeModeStreams.kt:121-124 reattaches and drains held model output for the returning result step.) [dep_h_attachment]
  - `CodeModeStreams` _attachment-lifecycle_
  - `CodeModeSwitchingSink` _serializes-output_
  - `CodeModeStreamingCell` _detaches-on-callback_
  - `CodexCodeModeMachine` _publishes-callback_
  - `CodexCodeModeResume` _reattaches-after-result_

**output-release** (ImmediateSseWriter.kt:52-54 flushes every written frame. Pacer holds at most a one-second window and counts delta frames, so one large upstream delta remains one atomic display (DeltaPacer.kt:85-98,136-148). Program generation visibility is intentionally absent (ResponsesExecProgress.kt:1-7).) [dep_h_client_flush]
  - `SseEmitter` _complete-frame-owner_
  - `ClientChannel` _ordered-socket-writer_
  - `DeltaPacer` _frame-count-pacing_

**feature-flag-fork** (All final text/tool operations wait for whole successful round, while thinking goes live. BufferingWireSink.kt:18-39; FoldRounds.kt:74-76; ResponsesTurnSeams.kt:109-116; core/.../config/Knob.kt:139-142.) [dep_h_fold]
  - `RoundStrategy` _caller_
  - `FoldRunner` _fold-enabled-target_
  - `BufferingWireSink` _tentative-final-buffer_
  - `SseEmitter` _unbuffered-target_

**completion-boundary** (Item done seals unread source. The completed success outcome certifies it. A callback already issued before item done can return early; a step meeting the seal must await certification. CodeModeSourceCapture.kt:49-57,120-145; CodeModeStreamingCell.kt:24-31.) [dep_h_source_certification]
  - `ResponsesStreamTranslator` _collector-and-outcome-owner_
  - `ResponsesEventReducer` _terminal-latch_
  - `CodeModeLiveRound` _certifies-outcome_
  - `CodeModeSourceCapture` _freezes-on-item-done_
  - `CodeModeSourceBuffer` _awaits-terminal-certification_
  - `CodeModeStreamingCell` _gates-callback-or-completion_

**stream-source-observation** (Script input is intentionally hidden from client; Started/Delta/Completed observations reach source capture. ResponsesExecProgress.kt:24-47; CodeModeSourceCapture.kt:45-85.) [dep_h_source_stream]
  - `ResponsesEventReducer` _upstream-event-dispatch_
  - `ResponsesExecProgress` _source-only-projection_
  - `CodeModeLiveRound` _independent-source-owner_
  - `CodeModeSourceCapture` _identity-and-size-checks_
  - `CodeModeSourceBuffer` _incremental-source_

**transport-terminal-scope** (SSE reads body EOF, while WS breaks after terminal event. SseReader.kt:58-75; WsRoundStream.kt:34-45. SSE [DONE] is skipped, not a terminating signal (SseDecode.kt:123-132).) [dep_h_transport_split]
  - `SseRoundDriver` _transport-selection_
  - `SseRoundConsume` _http-body-consumer_
  - `SseReader` _reads-to-body-eof_
  - `ResponsesStreamTranslator` _collects-flow-to-completion_
  - `WsRoundStream` _ends-flow-on-protocol-terminal_

**relation** (RoundStrategy.kt:98-104 invokes a RoundBodyInterceptor when available; controller serializes conversation before CodexCodeModeDriver.) [dep_interception]
  - `RoundStrategy` _caller_ [tail]
  - `CodeModeRoundInterceptor` _callee_ [head]

**relation** (Via CodexCodeModeTurn.run/ordinary, CodexCodeModeTurn.kt:83-123,135-159.) [dep_interceptor_driver]
  - `CodeModeRoundInterceptor` _caller_ [tail]
  - `CodexCodeModeDriver` _callee_ [head]

**relation** (CodeModeLiveRound.kt:48,108-132 redirects translated output to an attachable client step.) [dep_live_round_sink]
  - `CodeModeLiveRound` _caller_ [tail]
  - `CodeModeSwitchingSink` _callee_ [head]

**relation** (CodexCodeModeMachine.kt:193-195 advances the retained wrapped worker cell; dispatchStep.kt:247 handles callbacks.) [dep_machine_cell]
  - `CodexCodeModeMachine` _caller_ [tail]
  - `CodeModeStreamingCell` _callee_ [head]

**relation** (CodeModeStreams.kt:93-110 starts an independently scoped redirectable upstream source.) [dep_own_source]
  - `CodeModeStreams` _caller_ [tail]
  - `CodeModeLiveRound` _callee_ [head]

**relation** (ClientChannel.kt:157-168 holds visible bursts, preserving all subsequent frame ordering.) [dep_pacing]
  - `ClientChannel` _caller_ [tail]
  - `DeltaPacer` _callee_ [head]

**relation** (CodeModeSinkContent forwards attached output, or CodeModeSinkWrites buffers it while detached.) [dep_prose_output]
  - `CodeModeSwitchingSink` _caller_ [tail]
  - `SseEmitter` _callee_ [head]

**relation** (CodexCodeModeResume.kt:145-170 forwards newly accepted tool results into the same retained cell.) [dep_result_resume]
  - `CodexCodeModeResume` _caller_ [tail]
  - `CodexCodeModeMachine` _callee_ [head]

**relation** (Through CodeModeRuntimeStarter.start/open; JvmCodeModeRuntime.kt:111-136 reads the source and creates streaming or complete worker.) [dep_runtime]
  - `CodeModeSourceBuffer` _caller_ [tail]
  - `JvmCodeModeRuntime` _callee_ [head]

**relation** (Via CodeModeRedirectablePost/ObservedRoundPost; SseRoundDriver.kt:59-67 attempts WebSocket, then SSE.) [dep_source_post]
  - `CodeModeLiveRound` _caller_ [tail]
  - `SseRoundDriver` _callee_ [head]

**relation** (ResponsesEventReducer.kt:42-43,52 routes item added/done and custom_tool_call_input.delta to progress.) [dep_source_reducer]
  - `ResponsesEventReducer` _caller_ [tail]
  - `ResponsesExecProgress` _callee_ [head]

**relation** (Successful HTTP response is consumed in SseRoundConsume; SseRoundDriver.kt:66-67 via SseRoundPost.) [dep_sse_transport]
  - `SseRoundDriver` _caller_ [tail]
  - `SseRoundConsume` _callee_ [head]

**relation** (CodexCodeModeDriver.kt:46-63 begins a source with independent lifetime and awaits admission.) [dep_start_source]
  - `CodexCodeModeDriver` _caller_ [tail]
  - `CodeModeStreams` _callee_ [head]

**relation** (CodexCodeModeMachine.replay:143-156 opens and closes real client tool calls, returning hasToolUse.) [dep_tool_output]
  - `CodexCodeModeMachine` _caller_ [tail]
  - `SseEmitter` _callee_ [head]

**relation** (SseRoundConsume.kt:120-124 obtains provider translator and drives the reader-backed event Flow.) [dep_translator_events]
  - `SseRoundConsume` _caller_ [tail]
  - `ResponsesStreamTranslator` _callee_ [head]

**relation** (prepare installs the interceptor with current tool results and disableParallel, CodexCodeModeTurnBuilder.kt:50-64.) [dep_turn_bridge]
  - `CodexCodeModeTurnBuilder` _caller_ [tail]
  - `CodeModeRoundInterceptor` _callee_ [head]


## 2. Energy map — state containers and clearance

| Container | Key | Scope | Lifetime | Cleared-by | Trigger | Path |
|---|---|---|---|---|---|---|
| `ResponsesTurnState` | `energy_key_1` | one upstream response round | request | `SourceResponseLifetime` | SourceResponseLifetime | `integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesTurnState.kt` |
| `CodeModeSourceBuffer` | `energy_key_2` | CodeModeLiveRound source | request | `SourceResponseLifetime` | SourceResponseLifetime | `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeSourceBuffer.kt` |
| `CodeModeSinkCore` | `energy_key_3` | one source round across client attachments | request | `ClientAttachment` | ClientAttachment | `integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeSwitchingSink.kt` |
| `JvmCodeModeCell` | `energy_key_4` | one code-mode cell | user-session | `RuntimeCellLifecycle` | RuntimeCellLifecycle | `integrations/codemode/src/main/kotlin/splice/codemode/JvmCodeModeCell.kt` |
| `CodeModeStatementParser` | `energy_key_5` | one guest cell context | user-session | `RuntimeCellLifecycle` | RuntimeCellLifecycle | `integrations/codemode/src/main/kotlin/splice/codemode/CodeModeStatementParser.kt` |
| `SharedWorkerChannel` | `energy_key_6` | one shared host process generation | process | `RuntimeCellLifecycle` | RuntimeCellLifecycle | `integrations/codemode/src/main/kotlin/splice/codemode/SharedWorkerChannel.kt` |
| `CodexCodeModeRegistry` | `energy_key_7` | one reusable provider/head bridge | process | `RegistryRetention` | RegistryRetention | `integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeRegistry.kt` |
| `CodexCodeModeStore` | `energy_key_8` | per-conversation retained snapshots and state directory | app-install | `RegistryRetention` | RegistryRetention | `integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeStore.kt` |
| `DeltaPacer` | `energy_key_9` | one streaming client turn | request | `SocketPacing` | SocketPacing | `features/turns/src/main/kotlin/splice/head/wire/DeltaPacer.kt` |
| `CodexCodeModeMachine.started` | `energy_key_10` | one reusable bridge machine | process | _(none found)_ | RegistryRetention | `integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeMachine.kt` |

### ⚠ Incomplete clearance relations (possible leaks)

- `CodexCodeModeMachine.started` / `energy_key_10` has no clearer in scope.


## 3. State machine (from seed event forward)

### Transitions

**STATE: `Client callbacks emitted; source parked`**
  [CodexCodeModeResume.fresh -> registry.acceptResults -> machine.advance] when Next Claude Code request carries valid visible tool_result ids→ `Client results accepted; worker resumed`
  [CodeModeSinkWrites.deliver / drain] when Source reader continues after detach; detached commentary writes are byte-bounded and replay on the next attachment→ `Client callbacks emitted; source parked`

**STATE: `Client results accepted; worker resumed`**
  [JvmCodeModeCell.receiveAfter -> StreamingWorkerSession.drain] when Returned result makes a dependent callback ready; unresolved siblings remain pending→ `Executable callback ready`
  [CodeModeStep.Completed -> CodexCodeModeMachine.complete] when No pending callbacks remain and source is complete→ `Script completed; canonical history ready`
  [CodexCodeModeResume.interrupts / machine.poison / workerRecovery.lost] when Operator steering, unanswered-system interruption, missing tool catalog, runtime fault, or explicit bound ends the cell→ `Execution lost or interrupted`

**STATE: `Executable callback ready`**
  [CodeModeStreamingCell.advance -> CodexCodeModeCallIssuer.accept -> machine.emit] when Source is not sealed, or terminal certification has completed; pending ids persist before emission→ `Client callbacks emitted; source parked`
  [CodeModeStreamingCell.advance -> SourceBuffer.awaitCertification] when Item-done raced ahead of the selected statement; callback waits rather than escaping an uncertified source→ `Item complete; certification pending`

**STATE: `Script completed; canonical history ready`**
  [CodexCodeModeDriver.finishGenerated -> canonicalize -> post] when Canonical custom_tool_call + custom_tool_call_output history drives the next hidden model round→ `Round posted`

**STATE: `Execution lost or interrupted`**
  [CodexCodeModeResume.lost -> machine.interrupt] when Accepted evidence and unresolved calls are preserved as interrupted exec output; source is not rerun→ `Script completed; canonical history ready`
  [CodeModeLiveRound.outcome / permanentEnding / machine.poison] when Current request ends with original refusal/transport or deterministic protocol cause; no fabricated clean stop→ `Honest client terminal`

**STATE: `Item complete; certification pending`**
  [ResponsesEventReducer.onTerminal] when response.completed/done/incomplete sets finalResponse but does not finish the SSE Flow→ `Response terminal parsed; transport draining`

**STATE: `Round posted`**
  [ResponsesItemFold.onTextDelta / ResponsesReasoningFold] when Upstream text/reasoning arrives; visible blocks are written to attached sink→ `Streaming visible output`
  [ResponsesExecProgress.added -> CodeModeSourceCapture.begin] when custom_tool_call name=exec starts; record admitted and runtime startup begins→ `Exec admitted; source and worker streaming`

**STATE: `Honest client terminal`**

**STATE: `Response terminal parsed; transport draining`**
  [Flow completion -> CodeModeLiveRound.finish -> CodeModeSourceCapture.finish -> source.complete] when Successful complete response certifies exactly one matching exec source; pending source/callback waiter wakes→ `Executable callback ready`
  [Flow completion -> ResponsesStreamTranslator.finishRound -> outer pipeline] when No remaining exec callback or hidden continuation; final output finishes→ `Honest client terminal`

**STATE: `Streaming visible output`**
  [ResponsesExecProgress.added] when Commentary/reasoning is followed by an exec item→ `Exec admitted; source and worker streaming`
  [ResponsesEventReducer.onTerminal] when Terminal arrives after plain commentary/final text→ `Response terminal parsed; transport draining`

**STATE: `Exec admitted; source and worker streaming`**
  [ResponsesExecProgress.delta -> SourceBuffer.publish -> StreamingWorkerSession.append] when More source bytes arrive but there is no certified executable boundary, or dependency/hoisting/intrinsic checks defer it→ `Exec admitted; source and worker streaming`
  [CodeModeStatementParser.certifiedPrefix -> StreamingWorkerSession.run -> WorkerSession.reply] when Complete statement and dependency-safe operation call a permitted nested tool→ `Executable callback ready`
  [CodeModeSourceCapture.observe(Completed) -> SourceBuffer.seal] when output_item.done supplies matching full exec source; no response outcome yet→ `Item complete; certification pending`
  [CodeModeRuntimeStarter.failed] when Only typed CodeModeStartException proves runtime boot failed before dispatch→ `Retryable startup before dispatch`
  [CodeModeLiveRound.finish / failed / ended / died] when Source tears, fails, is refused, changes identity/prefix, has incomplete terminal, or reader dies→ `Execution lost or interrupted`

**STATE: `Retryable startup before dispatch`**
  [CodexCodeModeDriver.retryStart] when Exact retried request and retained whole/live source are available→ `Exec admitted; source and worker streaming`

### Terminal / stranded states

- `CODEMODE-RECURRING-FAILURES` — Reported recurring code-mode failures; a concrete argument-rejection recovery failure is visible in source
- `CODEMODE-STREAM-PAUSES` — Reported pause/burst symptom; conditional terminal-to-EOF latency mechanism confirmed in source, traffic prevalence unmeasured
- `CodeModeWorker.LAUNCHER.call`
- `Object serializes to null; script catches rejection`
- `Rejected argument leaves sequence unchanged`
- `ResponsesStreamTranslator.reduceRound`
- `Round outcome available immediately at terminal`
- `response.completed event`

### 🚩 Bug gap points

**CODEMODE-STREAM-PAUSES GAP #1** [contributing]
  - _state_when_gap_observed_: `Response terminal parsed; transport draining`
  - _triggering_event_: `response.completed event`
  - _responsible_handler_: `ResponsesStreamTranslator.reduceRound`
  - _expected_state_: `Round outcome available immediately at terminal`
  - _bug_: `CODEMODE-STREAM-PAUSES`

**CODEMODE-RECURRING-FAILURES GAP #1** [root-cause]
  - _state_when_gap_observed_: `Executable callback ready`
  - _triggering_event_: `Object serializes to null; script catches rejection`
  - _responsible_handler_: `CodeModeWorker.LAUNCHER.call`
  - _expected_state_: `Rejected argument leaves sequence unchanged`
  - _bug_: `CODEMODE-RECURRING-FAILURES`



---

## Synthesis

# Synthesis

This map records the original review at `ebd66db6a`. The four requested fixes are now implemented;
see [implementation.md](implementation.md) for the changes, regression coverage, and verification status.
The evidence and proposals below describe the code before those fixes.

## Working hypothesis

The review identifies two distinct protocol defects: rejected callback arguments can corrupt the accepted-call sequence, and Responses SSE completion waits for transport EOF after receiving the response terminal. Neither alone explains all reported pauses. Passive telemetry shows substantial quiet periods on current GPT WebSocket traffic before client pacing; hidden code generation and whole-frame delivery can further shape their visible stop-and-burst pattern.

This installation explicitly enables WebSockets in [splice.toml:95](/home/marcos/.config/splice/splice.toml:95). It tries WebSockets first and can fall back to SSE on eligible failures. The SSE defect concerns that fallback path here; it is not the explanation for the sampled WebSocket pauses. The shipped example configuration leaves WebSockets off.

This synthesis combines the merged source maps with the separately sourced Codex comparison and passive measurements in [investigation-notes.md](investigation-notes.md). During the original investigation, source scenarios were not executed; no tests, source changes, configuration changes, or restarts were performed. Subsequent implementation and verification are recorded separately above.

## Evidence

**1. A recoverable argument error becomes a cell failure.** The JavaScript launcher increments its sequence and inserts a pending promise before the host accepts the call. An object whose `toJSON()` returns `null` passes the launcher's initial object check, but the host requires the decoded arguments to remain an object. If the script catches that rejection and submits a valid tool call, its first emitted ID is `2`; the receiver requires consecutive accepted IDs beginning with `1`. The stale pending entry also remains. This is established by the source path, not a runtime reproduction: merged gap `h_gap_rejected_argument_sequence`; [CodeModeWorker.kt:183](/home/marcos/Documents/dev/projects/splice/repo/integrations/codemode/src/main/kotlin/splice/codemode/CodeModeWorker.kt:183), especially lines 193–199 and host validation at 158–171.

The comment at [CodeModeWorker.kt:70](/home/marcos/Documents/dev/projects/splice/repo/integrations/codemode/src/main/kotlin/splice/codemode/CodeModeWorker.kt:70) records the same failure class on October 1: a rejected call consumed an ID and poisoned the following call. The present validation path bypasses that earlier batching repair. Existing log tails did not contain the searched protocol-error messages; this does not establish whether this defect caused the user's incidents.

**2. Responses SSE waits beyond logical completion.** `ResponsesEventReducer` records the terminal response, but `ResponsesStreamTranslator.reduceRound` continues collecting its input flow. Source certification, round finalization, and dependent completion wait for that flow to finish. SSE reads until HTTP body EOF; WebSocket already ends at its terminal event. Thus an SSE connection remaining open after `response.completed` can delay final callbacks or the next hidden round. The actual terminal-to-EOF delay was not measured. Evidence: merged gaps `dep_gap_sse_terminal_eof` and `h_gap_terminal_eof`; [ResponsesStreamTranslator.kt:113](/home/marcos/Documents/dev/projects/splice/repo/integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesStreamTranslator.kt:113), [CodeModeSourceBuffer.kt:57](/home/marcos/Documents/dev/projects/splice/repo/integrations/providers/codex/src/main/kotlin/splice/provider/codex/stream/CodeModeSourceBuffer.kt:57), [WsRoundStream.kt:34](/home/marcos/Documents/dev/projects/splice/repo/integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/websocket/WsRoundStream.kt:34).

**3. The sampled WebSocket pauses mostly precede pacing.** Supplementary telemetry covers 398 non-compaction GPT-6.1 Sol source turns and 100 GPT-6 Luna turns. For Sol, the per-turn longest upstream wire gap had p90 **10.376 seconds**, versus p90 **54 milliseconds** for the longest client pacing hold and **104 milliseconds** for blocked upstream consumption. Those quantities measure different stages, but their scale rules out pacing as the sole explanation for this sample. The SSE defect does not explain these WebSocket gaps. The moving tail does not identify the reported incident or distinguish model computation from upstream service buffering. See the sampling criteria and missing-field counts in [investigation-notes.md](investigation-notes.md).

Several intentional behaviors can amplify visible batching:

- Code-mode source is hidden. Statements wait when boundaries or dependencies are unresolved; a callback meeting a sealed source waits for response certification. Source activity therefore need not produce visible output. Evidence: merged `energy_silent_source` and `dep_h_source_certification`; [ResponsesExecProgress.kt:39](/home/marcos/Documents/dev/projects/splice/repo/integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/stream/ResponsesExecProgress.kt:39).
- `DeltaPacer` schedules complete frames. A large visible delta following silence can pass immediately as one burst; its contents are not subdivided. Evidence: merged `dep_h_client_flush` and `energy_atomic_summary_burst`; [DeltaPacer.kt:68](/home/marcos/Documents/dev/projects/splice/repo/features/turns/src/main/kotlin/splice/head/wire/DeltaPacer.kt:68).
- Fold-enabled models hold final text/tool frames until the round succeeds. Default names are `gpt-5.6-luna,gpt-5.6-terra,gpt-5.5`, excluding both sampled GPT-6 models. Effective per-head settings would be needed to attribute their pauses to folding. Evidence: merged `dep_h_fold`; [BufferingWireSink.kt:18](/home/marcos/Documents/dev/projects/splice/repo/features/turns/src/main/kotlin/splice/head/wire/BufferingWireSink.kt:18).

The maps also identify a small timestamp cleanup gap: `CodexCodeModeMachine.started` can retain entries after autonomous park, TTL expiration, or head stop. It has no demonstrated connection to stutter and is lower priority (`energy_clr_10`; [CodexCodeModeMachine.kt:53](/home/marcos/Documents/dev/projects/splice/repo/integrations/providers/codex/src/main/kotlin/splice/provider/codex/CodexCodeModeMachine.kt:53)).

## Proposed fixes and Codex comparison

**First, make callback acceptance transactional.** Validate the serialized-and-decoded object before reservation; make host acceptance, sequence advancement, and pending registration share one commit/rollback boundary. A rejected call must leave no pending entry or accepted-ID gap. Keep the strict receiver check. Current [Codex callbacks.rs](https://github.com/openai/codex/blob/7b44ac1661433ae3a6f375fde873fa7123c935fe/codex-rs/code-mode-runtime/src/runtime/callbacks.rs#L27-L78) serializes before ID allocation; splice's additional host-side shape check makes acceptance ordering especially important.

**Second, finish Responses SSE on its processed protocol terminal.** Preserve usage capture, failure/incomplete classification, cleanup, source certification, and honest client termination. Apply the same semantic boundary already present in the WebSocket path. Current [Codex turn.rs](https://github.com/openai/codex/blob/7b44ac1661433ae3a6f375fde873fa7123c935fe/codex-rs/core/src/session/turn.rs#L3060-L3121) records completion and exits sampling without waiting for body EOF. This fixes a separate conditional delay, not the sampled WebSocket silence.

**Third, measure large-frame delivery in an affected session before changing smoothing.** If large frames dominate visible bursts, split text/thinking deltas into smaller ordered deltas before pacing, preserving content, Unicode boundaries, block indices, and terminal ordering. Codex uses [adaptive terminal rendering](https://github.com/openai/codex/blob/7b44ac1661433ae3a6f375fde873fa7123c935fe/codex-rs/tui/src/streaming/chunking.rs#L1-L41); splice schedules protocol frames. Neither can display content before it arrives.

Codex [evaluates the whole JavaScript module](https://github.com/openai/codex/blob/7b44ac1661433ae3a6f375fde873fa7123c935fe/codex-rs/code-mode-runtime/src/runtime/mod.rs#L225-L234). Splice executes certified statements while the rest streams, adding parser, rewriting, dependency, and certification machinery. Early side effects before a later syntax error are a policy tradeoff of that design. Changing that policy requires an explicit latency-versus-validation decision; it is not another demonstrated accidental defect. Retain the deliberate decision to hide source.

The highest-value follow-up is correlating one affected session's upstream wire/content gaps, statement readiness, emitted frame sizes, and client writes. That would separate upstream silence from hidden generation and local delivery without forcing these distinct mechanisms into one root cause.
