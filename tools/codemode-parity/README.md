`bun tools/codemode-parity readiness` is the product qualification check. It exits
nonzero until every frozen contract family has executable evidence for the exact
candidate and the installed daemon's open jar/native executable match it.
`progress.json` reports that state explicitly. Readiness and install attestation use
its last receipt location unless `--evidence` is provided; the pointer only selects
a file and never bypasses qualification. Malformed progress fails closed. Normal gate tests validate the
qualification guard; their green result does not qualify the product.

Run model-free native observations with:

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
