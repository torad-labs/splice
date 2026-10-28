`bun tools/codemode-parity readiness` requires current complete CodeMode evidence
for the reference in `current-reference.json`. The frozen 24-family contract and
schema-1 receipts remain a historical regression floor. They cannot satisfy this
command, even when their saved status is `QUALIFIED`. The current command writes
`progress-current.json`; `progress.json` remains the bounded receipt pointer.

`current-inventory.ts` reads immutable Git objects at the exact reference commit.
It discovers CodeMode crates and modules across the reference tree and parses
CodeMode-bearing consumers, Rust functions/types/fields/variants, embedded MCP
TypeScript types/fields, JavaScript helpers and all protobuf RPC/schema members.
`current-inventory.json` seals their source hashes and complete denominator.
`current-dispositions.json` must contain exactly the generated unit set, with each
unit's body hash and ownership/file-scope hash. Regeneration preserves only an
already reviewed, byte-identical mapping. New helpers, changed function branches,
changed ownership, and changed containing files become unsupported until reviewed.
Ported function credits require named input hashes, an exact source entrypoint,
the production entrypoint, and actual function-entry counters for those cases.
The oracle preserves original Git bytes and an auditable list of counter-only
insertions. Readiness reconstructs those insertions and checks both raw case
outputs, input identity, nonce and source counters. A passing verifier family
cannot satisfy an unlinked declaration. This is function-entry evidence, with
no statement or branch coverage claim. Types, fields and other behavioral units
without an explicit executable relation remain blockers. Native reuse requires
the actual host-owned source closure, verified source linkage and exact candidate
executable. Remote gRPC client
implementation details, application flags, analytics and TUI presentation retain
explicit context dispositions; they are not claimed Kotlin ports. Shared local
session and callback behavior remains mandatory executable evidence.

The normal push gate reruns inventory validity. A fresh checkout automatically
fetches the exact official source tag into the user cache and checks its commit;
`--source-repo` or `CODEX_REFERENCE_REPO` can reuse an existing clone read-only.
Original metadata, signature bundles and historical verification reports are
tracked under `current-provenance/`. Reports do not authorize provenance. Each
check decompresses the tracked native payloads and reruns pinned Cosign 3.1.4
against both Linux executables using standard bundles and a pinned offline
Sigstore trust root. Signature, certificate identity, source workflow SHA,
transparency inclusion and certificate transparency checks remain enabled.
Cosign is automatically downloaded to the user cache with an immutable SHA pin.
For macOS and Windows, each check obtains official Actions workflow/artifact
metadata over GitHub's API, checks the exact source SHA and archive digest, then
extracts the real digest-verified archive and compares executable bytes. The
Windows wheel is extracted as a nested ZIP. `gh` authentication with public
artifact access is required for these API/download requests. Archives bootstrap
automatically into `$XDG_CACHE_HOME/splice/codemode-provenance`; an existing cache
can be selected with `CODEMODE_PROVENANCE_CACHE`. No operator's historical
absolute artifact directory is required. Missing proof/cache/network fails closed.

The current reference driver uses the official host's `grpc+stdio://` transport
with pinned gRPC dependencies and the exact reference protobuf schema. Raw
independent streams and optional `input_json` presence are preserved. A dedicated
discriminator requires omitted function input to become `{}` and explicit JSON
`null` to fail argument admission. The legacy framed host transport loses this
distinction and is used only for historical qualification.

Current readiness rechecks the selected installed Codex package, CLI version,
CLI/native hashes, verified release/source evidence, inventory, mappings, exact
candidate, raw evidence hashes, harness identity, run age and seal. It resolves
Splice's configured control port through the installed jar's own settings code,
then independently finds the listening socket owner. Boot ID, process birth,
service MainPID, open installed jar and owned native host are bound and rechecked.
Supplying an old still-live PID cannot select the service.

Generate/check inventory and execute the current qualification explicitly:

```sh
bun tools/codemode-parity/current-inventory.ts /absolute/reference-git
bun tools/codemode-parity/current-coverage.ts
bun tools/codemode-parity inventory-check
bun tools/codemode-parity qualify-current \
  --java /absolute/java \
  --candidate-jar /absolute/candidate/splice.jar \
  --candidate-host /absolute/candidate/codex-code-mode-host \
  --baseline-jar /absolute/frozen-baseline/splice.jar \
  --evidence /absolute/current-artifacts
```

Compiler-backed source selection is explicit: add
`--compiler-bindings /absolute/reviewed-proof-manifest.json` to inventory generation,
`inventory-check`, `qualify-current`, `attest-install-current`, and `readiness`.
An inventory that records a compiler proof requires that argument for reproduction.
The manifest's exact hash and canonical source-binding digest are recorded in the
inventory and current receipt; paths only locate the hash-checked proof artifacts.
Missing or changed proof, compiler, source, lock, input, or fact bytes fail validation.
There is no implicit compiler cache. Exact positive source bindings can refine an
AST guess and discover an immutable target file. Missing, generated, dynamic, and
generic facts retain conservative AST selection with explicit debt. These bindings
select obligations and never grant behavioral coverage.

The current qualifier reruns the frozen regression floor against the current
reference and adds source-based core/schema/description/BM25 comparisons and
current protocol discriminators. The historical formatter remains an explicit
old-source regression; the supplemental core verifier executes the current
header, response adapter, image policy and truncation source. Its Rust dependencies
are locked and compiled offline with Rust 1.99.1, matching the official binary's
compiler commit. A missing toolchain bootstraps through rustup without changing
the user's default. Original Snowball vocabulary, Unicode and word-boundary inputs and all
ordered candidate/reference results are retained. Linux deployment execution and
verified native identities on other packaged targets are separate claims.
`known_soft_spots` records limits without converting them into successful checks.
Normal gate tests validate the verifier mechanism; their green result does not
qualify the product.

The explicit ledger currently contains 70 source functions reached by 412 unique
base oracle inputs (432 vectors included 20 identical inputs), 1,264 native-owned
declarations, and 1,278 context/accounting units. It leaves 2,232 declarations unlinked. These are completeness blockers,
including conservative shared helper/type census entries that still require a
source applicability decision. They must be resolved with a concrete witness or
an explicit source predicate; the earlier path-family assignments do not count.

Candidate conformance runs before installation and exits successfully as
`CANDIDATE_CONFORMANT` only after every candidate proof passes. Its sealed receipt
keeps `qualified: false`, so default readiness stays unqualified. After installing
and restarting the unchanged candidate, finalize actual service identity without
repeating conformance:

```sh
bun tools/codemode-parity attest-install-current --evidence /absolute/current-artifacts
bun tools/codemode-parity readiness
```

Execution receipts live outside the checkout. Generated progress is excluded from
the harness hash, so saving receipt pointers cannot change their source bounds.

The historical bounded commands below remain available for regression work.
Use `readiness-bounded` to validate their schema-1 receipt. Run model-free legacy
native observations with:

```sh
bun tools/codemode-parity/reference-suite.ts /absolute/codex-code-mode-host /absolute/artifacts
```

The actual standalone executable is pinned in `reference-lock.json`. Its source
checkout is recorded separately: binary-to-source linkage is unproven. Direct
host observations cover runtime behavior; they cannot establish gateway item
admission, durable model history, core output rendering, or interactive client
delivery. The host protocol returns 20,000 characters for `max_output_tokens: 20`;
`formatter-cases.ts` separately executes the pinned upstream Rust truncation
algorithms and policy methods, then compares their output with the candidate's
production formatter. Typed-content persistence through the provider is another
required dimension. `audio-cases.ts` compiles the untouched upstream audio,
cache and string modules against Symphonia 0.6 and generates real WAV, MP3, M4A,
Ogg and WebM samples with ffmpeg. It compares exact native token estimates at
keep/drop boundaries, including fragmented MP4, chained Ogg and zero cost.

Execute the production candidate adapter and native oracle together with:

```sh
bun tools/codemode-parity qualify \
  --java /absolute/java \
  --reference-host /absolute/reference/codex-code-mode-host \
  --candidate-jar /absolute/candidate/splice.jar \
  --candidate-host /absolute/candidate/codex-code-mode-host \
  --baseline-jar /absolute/frozen-baseline/splice.jar \
  --evidence /absolute/artifacts
```

The adapter runs through `splice.app.MainKt --internal-code-mode-parity` in the candidate jar.
It speaks host protocol V1 over little-endian length-prefixed JSON and exercises
the production runtime. Its first argument is the expected native executable;
the production packaged resolver chooses the actual executable. The harness
independently hashes the adapter's open jar and live native child executable.
No arbitrary adapter command or imported PASS receipt is accepted.

The runner creates a fresh random run identity, raw sent/received traces with
monotonic timestamps, artifact hashes and a locally sealed receipt. Readiness
checks the seal, complete case inventory, concrete verifier registry, contract
and harness hashes, artifact contents, executable identities and evidence age.
A missing driver stays explicit in `verifiers.ts`; it cannot be waived by
editing a receipt. Local sealing detects receipt edits; it is not an attestation
against someone who can modify this checkout and its Git-private seal key.

Qualification includes eight real interactive Claude client flows: success,
late failure, native lifecycle, restart, three native batch variants, and one
native sequential-script flow. The batch and sequential flows require real Bash and
Read callbacks and preserved results. Gateway probes separately distinguish
acknowledged replay from unknown reasoning and reject an unaccepted cleared
result as authority. These are independent checks; a passing client flow does
not prove that an older history bug was reproduced.

Run the harness guard tests with `bun test tools/codemode-parity`. The normal
push gate runs the same whole directory, including guards against incomplete
replay, clearing, and interactive evidence. A green guard suite checks the
verifier; the sealed candidate run still has to pass readiness.

Post-install qualification additionally requires `--installed-pid`, `--host-pid`
and `--jar-fd /proc/PID/fd/N`. The installed jar path alone is insufficient.
Readiness is deliberately separate from the build gate so installation does not
depend on evidence that can only exist after installation.

After the other 23 families pass, `attest-install` adds the fresh installed
identity to that sealed run without repeating its execution. It refuses changed
candidate, reference, harness, contract, stale or damaged artifacts, or any
unresolved family other than installed identity:

```sh
bun tools/codemode-parity attest-install --evidence /absolute/artifacts \
  --installed-pid PID --host-pid NATIVE_PID --jar-fd /proc/PID/fd/N
```
