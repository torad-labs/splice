# Compiler source bindings

`emit.rs` runs inside the exact pinned Rust compiler. Its `after_analysis`
callback records HIR resolutions, THIR function targets and `tcx.def_span`
declarations, then stops before code generation. External declaration spans
come from the metadata actually loaded by that compiler. A displayed Rust path
is diagnostic text and is never a lookup key.

The emitter requires `SPLICE_COMPILER_PROJECT_ROOT` and
`SPLICE_COMPILER_FACTS`. It emits complete JSONL with a final count record and a
256 MiB limit. A failed or truncated emission cannot be loaded. The task runner
must also enforce its memory and time limits; those are recorded in the pinned
analysis receipt rather than imposed on unrelated compiler processes.

`loadCompilerBindings` accepts the `rustc-driver-v1` proof format declared in
`current-compiler-driver.ts`. The caller supplies the exact proof hash, source
commit/tree, original and derived Cargo lock hashes, compiler hash/version and
immutable source bytes. The loader verifies the driver, compiler components,
cached extern metadata, analysis inputs, argv, complete fact stream and original
compiler source hashes. It preserves the compiler's original UTF-8 byte spans,
including declaration header prefixes.

Identity is scoped to the proof and analysis crate, then the complete compiler
stable crate ID, crate hash and definition index. No shortened debug suffix,
crate name, module path or method name can bridge analyses. Namespace, reference
span, kind and owner remain distinct. Dynamic, generic and trait-contract calls
remain unresolved. Missing source, generated spans and ambiguity retain debt.

The reader guards use actual compiler-emitted facts from the synthetic
`fixtures/compiler-driver` sources. Its manifest records their original compiler,
driver, external metadata, commands and output hashes; reader tests use separate
dummy identity inputs and do not certify executable behavior. Current-source
proofs and raw emissions stay in the task artifacts. Compiler source bindings
never grant behavioral coverage or remove unmapped inventory obligations.
