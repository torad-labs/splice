# Interactive final evaluation review plan

Status: preparation only. No new model calls are authorized by this document. Final binary identity is pending the source-order correction, its regression proof, complete normal gate, same-binary interactive probe v3, fresh independent review, and explicit run GO.

## Reviewed scope

The accompanying patch contains only the interactive launcher/supervisor/session reader, model-free fixtures/tests, invocation/comparison/provenance/report guards, strict title-request metadata classifier and exact terminal dependency changes. It does not include unrelated GLM documentation, prior long-context work, task verifier or capture changes. Before/after hashes and explicit owned paths are in source-hashes.json. The original cumulative Oct14 patch and full overlay are PRIVATE AND NOT APPROVED FOR PUBLICATION.

## Fixed evaluation: 48 trials

Five unchanged micro tasks x three repetitions x native Codex and Splice =30 trials. Three unchanged public tasks x three repetitions x both arms =18 trials. Run micro before public, serially with the runner's alternating client order. No diagnostic/smoke/retry additions without authorization. Every failure, interrupted attempt, capture and terminal artifact stays in its original private receipt.

Use the same approved frozen binary for both suites; verify its SHA256 before each launch and record the public source revision. Preserve pinned Codex0.159.1 and Claude2.1.299 hashes, repository-tools surface, focused-exploration guidance, GPT6.1Sol/high, detailed/all-turns task reasoning, replay=true, WebSocket prefill=true, both captures, task hashes, images and account equality. No credentials or account identifiers belong in public evidence.

New invocation controls: interactive-pty-v1; Bun1.4.3; 120x40 xterm terminal; @xterm/headless5.5.0; genuine native completion artifacts; original per-client bypass permission controls; adapter/client/completion/classifier hashes and exact dependency-lock hash. Unknown auth/permission prompts are not accepted. Native task completion precedes Ctrl-D; a second Ctrl-D requires its explicit UI cue. The only configured startup consent is the exact prompt for the runner-owned local gateway token.

Commands below are documentation, not an execution grant. Working directory is BENCHMARK_PACKAGE; use Bash with login:false. Variables must name the approved package, public source checkout, frozen jar and unique revision label.

```bash
cd "$BENCHMARK_PACKAGE"
common=(--repo "$SPLICE_REPO" --jar "$APPROVED_JAR"
  --arms codex,splice --repetitions 3
  --replay-reasoning true --ws-prefill true
  --capture-requests --capture-instructions
  --guidance tools/agent-bench/guidance/focused-exploration.txt
  --surface-file tools/agent-bench/profiles/repository-tools.json)
bun tools/agent-bench run "${common[@]}" --suite micro --max-trials 30   --label "interactive-${APPROVED_REV}-micro"
# Launch only after the micro health review permits its dependent suite.
bun tools/agent-bench run "${common[@]}" --suite terminal --max-trials 18   --label "interactive-${APPROVED_REV}-public"
```

## Evidence and stop conditions

- Correctness remains the unchanged deterministic task verifier; exit/completion/timeouts are separate. A quiet terminal or exit0 without native completion is a failure, not a fabricated final answer.
- Keep terminal.raw, timestamped terminal-events.jsonl, rendered terminal-screen.txt, genuine native session JSONL and the source final message. events.jsonl is a byte copy of actual native rows. Never synthesize model output from terminal text. Wire captures remain the source of partial delivery and upstream timing.
- Capture integrity, complete terminal responses, settings and journal health must pass. Stop dependent launch for real loss, duplication, history-order inversion, incomplete captures, unaccounted callbacks or client failures; diagnose before editing or paying for another trial.
- Review the corrected R1/preface/R2 native-history ordering and restored cache behavior using the explicit source regression and same-binary interactive probe; generic benchmark task success alone is insufficient proof of those invariants.
- Readable model output must reach transcript/wire once and in order. Account explicitly for the pre-existing paragraph separator between summary parts. Opaque continuity carriers do not expose model reasoning and may not create new empty client rows.
- Known title-generation auxiliary requests require both the sole-title structured_output schema and naming developer instruction. Retain them separately with original captures and settings; never infer missing defaults or classify unknown requests as matched task generations. The historical analyzer/captures remain byte-preserved; interactive analyses use fresh versioned outputs.

## Reporting

Report n/mean/median/min/max and all individual times per task/arm; show correctness, capture integrity, native summaries returned, gateway-readable delivery, journal health and item-done/terminal/client-tool timing separately. Current native and Splice interactive samples form the paired comparison. Old headless measurements remain historical evidence and cannot be pooled or called comparable; comparison/resume guards enforce this. Three samples per case are directional, not a universal parity/speed claim.

## Model-free validation and limitation

The existing final receipt records50/50 model-free tests, strict TypeScript and offline help passing. These include real BunPTY to DockerPTY paths for both synthetic native record profiles, cursor query handling, source artifact byte-copy, guarded startup/exit, timeout/reaping and incompatible-mode refusal. No actual pinned-client model-backed session was started by these tests. Parent-reported probe v3 success remains separately identified and must match the eventual final binary before evaluation.
