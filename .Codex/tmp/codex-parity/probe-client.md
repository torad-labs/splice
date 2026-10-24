# Interactive client fixture

Run from the repository root:

```sh
bun .Codex/tmp/codex-parity/probe-client.ts --jar /absolute/candidate.jar --out /fresh/receipt.json
```

Version 3 runs the pinned Claude Code 2.1.310 **interactive TUI** in a real 160 × 60 PTY. It uses an isolated home, configuration, state directory and synthetic credentials, a candidate daemon, and loopback scripted Responses endpoints. It does not invoke a live model. Existing receipts are never overwritten.

The fixture emits a readable reasoning item and one complete `exec` program. It holds the response terminal for two seconds after item completion. The program calls the real client's Bash tool to append an unguarded timestamp to a temporary file. Separate success and late-provider-failure scenarios require:

- Exactly one filesystem effect, after complete-item admission and before the response terminal.
- Exact reasoning text once on the gateway wire and in the client's persisted thinking blocks, with no redacted blocks.
- The readable reasoning and final answer received by the PTY, and the final answer persisted by the client.
- The encrypted reasoning retained on the follow-up request, a clean interactive exit, and no infrastructure errors.

PTY output is not a screen-paint latency measurement. Native-prefix order across interleaved reasoning, omitted client echoes and a daemon restart is covered by the production-path Kotlin regressions. This fixture does not force retries or restart the daemon mid-script.

The TUI's separate structured session-title request receives a bounded synthetic title response. An unrecognized tool-free request still fails the fixture. Permission mode is explicitly `default`, with only Bash allowed; the fixture approves only its own fixed synthetic API key at the TUI's startup prompt. It exits through the normal Ctrl-D confirmation after the final answer is persisted and received by the terminal.

Receipts record the candidate, client, probe and PTY-helper hashes and the invocation mode. The isolated temporary home retains the client's genuine session JSONL and a private terminal diagnostic. The process environment is allowlisted, but loopback configuration is not an OS network sandbox.

Version 2 used print mode. Its frozen source and receipts are historical evidence and must not be rerun under the governing prohibition on headless sessions. Interactive and print-mode timings use different client paths and must not be pooled.
