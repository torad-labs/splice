# Interactive benchmark runner review

These are the scoped source changes to the sibling benchmark runner, provided for independent review before the final paired run. The runner starts both pinned clients as actual interactive TUIs through Docker PTYs. It retains genuine session JSONL, terminal bytes, terminal screen state, captures and task-verifier results.

`interactive-runner-review.patch` contains only the interactive adapter, its model-free tests, launcher/report wiring and invocation-mode provenance. `source-hashes.json` identifies the exact pre-interactive files and resulting source. Its eight existing-file bases were checked against the retained runner used for the earlier measurements; the other nine paths are additions. The patch was applied to matching base files and all resulting hashes checked. Task verifiers, capture transport, MCP server and container images are unchanged.

The sibling runner's 50 model-free tests, strict TypeScript check and CLI help check passed. Actual model runs remain gated on the repaired gateway's normal check, frozen version-3 interactive client probe and a fresh independent PR review. `interactive-evaluation-plan.md` fixes the final 30 micro and 18 public trials, controls, stop conditions and analysis limits. Both arms use the new invocation mode; historical print-mode timings are kept separate.

The artifacts omit unrelated sibling-folder edits and contain no raw session captures or credentials. Source overlays and historical diagnostic bundles remain private evidence.
