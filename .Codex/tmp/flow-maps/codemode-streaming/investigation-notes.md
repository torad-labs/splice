# Supplementary observations from the parent investigation

These observations describe the original source review and passive inspection of existing telemetry at splice commit `ebd66db6a`. At that stage no runtime reproducer, unit test, benchmark, configuration change, installation, or restart was performed, and no tracked source files were edited. See [implementation.md](implementation.md) for the subsequent fixes and executed validation.

The installed topology explicitly sets `websocket = true` under `[providers.codex.quirks]` at `/home/marcos/.config/splice/splice.toml:95`. This installation therefore tries WebSockets first; eligible failures can fall back to SSE (`features/turns/src/main/kotlin/splice/head/transport/SseRoundDriver.kt:59`). The example topology still ships WebSockets off (`app/src/main/resources/splice.example.toml:97`). The SSE completion defect is a fallback-path finding here and does not explain the sampled WebSocket pauses.

## Comparison with OpenAI Codex

The local checkout is `/home/marcos/Documents/dev/infra/coding/codex`, commit `17a9df60e420b58e3edc55efb1bb052e39492bbc`, dated September 29, 2026. The relevant behavior was also verified against current upstream main, pinned to `7b44ac1661433ae3a6f375fde873fa7123c935fe` (October 17, 2026), using GitHub file retrieval.

- Codex finishes its sampling receive loop in the `ResponseEvent::Completed` branch. It records usage and immediately breaks with a sampling result, without awaiting HTTP body EOF. See [current turn.rs](https://github.com/openai/codex/blob/7b44ac1661433ae3a6f375fde873fa7123c935fe/codex-rs/core/src/session/turn.rs#L3060-L3121). Splice's Responses translator continues collecting after its terminal reducer latches the response. Splice's WebSocket flow already has a terminal cutoff; the gap is the SSE path.
- Codex serializes callback input before allocating its callback ID or inserting its pending promise. See [current callbacks.rs](https://github.com/openai/codex/blob/7b44ac1661433ae3a6f375fde873fa7123c935fe/codex-rs/code-mode-runtime/src/runtime/callbacks.rs#L27-L78). Splice additionally requires the decoded input to be an object, but makes that final check in the host after the JavaScript launcher consumes its ID. Its strict consecutive-ID protocol turns a caught argument rejection into a later infrastructure failure. The proposed recovery scenario in the state map is source-grounded and has not been executed.
- Codex evaluates the whole source as a JavaScript module, using V8's module evaluator. See [current runtime/mod.rs](https://github.com/openai/codex/blob/7b44ac1661433ae3a6f375fde873fa7123c935fe/codex-rs/code-mode-runtime/src/runtime/mod.rs#L225-L234). Splice parses and rewrites individual source statements for execution while the remaining program streams. That introduces a separate statement-boundary, binding, intrinsic-read, and source-certification layer. This is a structural difference, not evidence that every pause is a defect. Early side effects preceding a later syntax error are a consequence of this policy.
- Codex's terminal renders queued lines with an adaptive display policy. See [current chunking.rs](https://github.com/openai/codex/blob/7b44ac1661433ae3a6f375fde873fa7123c935fe/codex-rs/tui/src/streaming/chunking.rs#L1-L41). Splice's `DeltaPacer` handles complete SSE frames. A single large visible delta after a quiet period is written immediately and cannot be smoothed by the existing per-frame budget. Neither policy can display model content that has not arrived.

Official OpenAI documentation identifies `response.completed` as the completed-response event and describes incremental custom-tool input events: [Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events).

## Passive telemetry sample

The sampled tail of `StatePaths.perfStatsFile("claudex")`, resolved to the adopted legacy state directory on this machine, contained current GPT-6.1 Sol and GPT-6 Luna traffic. The earlier snapshot of the same 1 MiB tail covered October 18, 2026, 01:18:25 through 01:52:39 UTC (October 17 local time). Counts below come from a slightly later snapshot; the tail is a moving window, not a fixed or representative experiment.

Only non-compaction rows with `arrival_to_ws_send_accepted_ms` present are included below. They identify upstream WebSocket source turns, excluding local code-mode follow-up rows without an upstream send. Quantiles use the recorded values present for each field. The measurements describe per-turn maximum gaps/holds, not average token spacing.

| Recorded quantity | GPT-6.1 Sol p50 / p90 / max | GPT-6 Luna p50 / p90 / max |
|---|---:|---:|
| Source turns sampled | 398 | 100 |
| First visible delta | 8.362 / 18.343 / 52.507 s | 3.205 / 4.388 / 158.376 s |
| Longest upstream wire gap per turn | 4.638 / 10.376 / 15.345 s | 2.444 / 4.281 / 126.684 s |
| Longest upstream content gap per turn | 6.449 / 17.194 / 48.967 s | 3.183 / 5.751 / 126.730 s |
| Longest blocked upstream-consumer interval | 62 / 104 / 476 ms | 80 / 113 / 185 ms |
| Longest client pacing hold | 0 / 54 / 271 ms | 0 / 17 / 834 ms |

First-delta values were present on 393 of the 398 Sol rows; hold values on 395. The other fields were present on every sampled source row. Luna had all listed fields on all 100 rows.

Interpretation: the sampled long quiet periods already exist before the client pacing queue; pacing is much shorter. Hidden code generation and statement readiness can further widen the gap between upstream activity and visible client output. The SSE EOF defect does not explain these sampled WebSocket gaps. The sample does not identify the exact incident reported by the user, distinguish model computation from server-side buffering, or measure terminal-to-EOF delays on SSE.

The current and preceding daemon log tails (up to 4 MiB each), and the stderr log tail, had no matches for the static invalid-call-ID/nesting-overflow/source-reader-death messages searched. This does not establish absence of those errors in transcripts or older logs.

## Established behavior and proposed priorities

1. Make a rejected callback leave the accepted-call ID sequence and pending map intact. Validate the decoded argument object before reservation, and make registration/host acceptance transactional. Keep the strict protocol check.
2. Finish a Responses SSE round when its protocol terminal is processed, with the same usage, error classification, cleanup, and certification that already run at EOF. Preserve honest incomplete/failure handling and byte-exact wire contracts.
3. For visible stutter, consider splitting large visible text/thinking frames into ordered smaller deltas before applying pacing. Preserve content bytes, block indices, Unicode boundaries, and stop ordering. First measure the large-frame share in an affected session.
4. Keep the October 12 decision to hide code-mode source. Its implementation is intentional. Source generation plus dependency/async deferral can leave the client quiet until tool callbacks become available.
5. Fold-enabled models buffer final text/tool frames until the round succeeds (`BufferingWireSink`, `FoldRounds`). The default fold names are `gpt-5.6-luna,gpt-5.6-terra,gpt-5.5`; they do not include either GPT-6 model observed in this telemetry sample. Do not attribute this sample's pauses to folding without effective per-head configuration evidence.

The maps also identify a small lifecycle cleanup gap in `CodexCodeModeMachine.started`; it has no demonstrated relationship to the reported stutter and is lower priority than the two protocol defects.
