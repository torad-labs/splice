The current CLI producer runs the pinned Codex executable in a real PTY against a
fixed loopback Responses server. It performs no model inference. Completion must
come from the one owned conversation's native `task_complete` event, and Linux
`/proc` must show a descendant executing the pinned host bytes. Binary hashes are
checked before and after execution. The caller supplies the model catalog from
the pinned source revision; its exact bytes and hash are retained.
The CLI's actual PID, `/proc` executable hash, process birth tick and exit status
are retained in the result and terminal process artifact.

`runCliScenario({reference, scenario, directory, catalog})` requires a **new**
directory and creates a private client home, fresh nonce, fixed configuration,
raw requests/frames, native transcript, terminal evidence and process identities.
It consumes all declared actions once and matches the final event to that nonce.
The producer retains failed observations and never interprets terminal text as a
native event. Auxiliary task titles need both the exact title instruction and
the sole-property JSON schema.

`controlScenarios` covers store/load, yield/wait/exhaustion, termination followed
by a healthy cell, notifications, two outer calls in one response, and silent
output. `gatewayInputBytes(scenario)` supplies those same scripts/operations to
the packaged application's existing `runtime.current-scenario` diagnostic.
`compareGatewayScenario` binds the source nonce and exact input bytes, uses only
the candidate JAR on its classpath, requires a verified native launch and actual
successful gateway rounds, and compares the call/result sequence. It omits only
generated output-item IDs and binds only the exact first status-header wall-time
measurement; output types, caller bytes, results and order remain material.

These control pairs exclude the frontends' unused tool catalogs and standing
instructions. They do not replace the source inventory, individual API/value
witnesses, original 24-family cadence/recovery floor or installed identity.
`mcpScenarios` records actual MCP and ranked-search reference behavior, including
the source's typed MCP result envelope. The generic Anthropic text callback
fixture rejects those cases: the equivalent typed boundary needs its own pair.

Cheap guards: `bun test tools/codemode-parity/current-cli/guards.test.ts`.
Type check: `node node_modules/typescript/bin/tsc --noEmit -p tools/codemode-parity/current-cli/tsconfig.json`.
Final verification uses the exact unmodified normal-gate JAR; development
classpath overlays cannot establish qualification.
