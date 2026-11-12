# Actual current core API reference

These files are reference inputs and source adapters, not a qualification receipt or saved PASS. The permanent runner must execute both the reference and the candidate again.

Copy this directory into a run's reference directory and put an immutable detached official Codex source worktree beside it as `../source`. Cargo path dependencies use the actual codex-tools, codex-protocol, codex-code-mode and codex-code-mode-protocol libraries. `Cargo.lock` is the pruned official lock closure. The build context is Rust 1.99.1, resolver 2, serde_json alloc/preserve_order/arbitrary_precision/raw_value + default/std, and debug/overflow checks false. LLVM function instrumentation may be enabled by the runner.

`source-extractions.json` records unchanged original Git spans/hashes and local offsets. The wait factory, catalog schema parser, generic argument parser and prepare-definitions helper are whole source functions. The preparation trait shell supplies runtime=None and claims no cached-runtime behavior. `response_adapter.rs` is a whole unchanged file with actual external types. Search validation is a statement fragment, not evidence of complete handle_call execution.

`jobs.jsonl` is the runner contract. Each row supplies the actual reference input, production probe input, explicit result paths and production entry point. It contains 339 observations: 116 complete direct comparisons, 55 typed shape observations and 168 model metadata observations. Numeric input lexemes are preserved. There are no inherited member-coverage claims.

Fixture groups:

- `direct-input.jsonl`: 116 complete-result comparisons for prefixes, catalog_wait and tool_search_arguments.
- `metadata-input.jsonl` and `metadata-projections.json`: 20 actual typed model cases, compared at explicit metadata fields in parser and cache output. Jobs compare actual present fields, identities and model counts. Missing optional descendants receive no field-coverage claim. Two wider frontend/legacy-instruction differences are recorded separately and receive no CodeMode-conformance claim.
- `typed-input.jsonl`: 43 actual typed-source inputs used by `paired-jobs.jsonl`'s 55 concrete candidate observations. Jobs contain reference selectors, not saved expected values.
- `source-only-input.jsonl`: 51 remaining malformed or independent protocol observations. These receive no candidate runtime PASS.

Reference selectors are `value` => result.value, `adapted_content` => result.adapted, `prepared_spec` => {prepared: result.prepared_by_spec[index]}, `wait_declaration` => {declaration: result.wait}, and `exec_description` => {description: result.description}.

The legacy selector descriptions above explain `paired-jobs.jsonl`; `jobs.jsonl` uses only explicit paths. Media jobs compare the actual adapted item at reference path `adapted[0]` with production path `content[1]`. They claim that item, not the output array length or its separate status header. Candidate inputs run through the existing packaged current-core fixture endpoint and its named production entry points. Field claims must bind individual values or exact rendered fragments; they never inherit an enclosing function's result.
