# Native MCP hub stand-in qualification

The runner records artifact-specific evidence. A prepared command or a successful
build is not a qualification. The earlier bbe7a874c receipt proves that pair only.

This retained procedure uses a real isolated splice daemon, socket-activated
native fronts, one native holder, and a stateful stdio counter. It sends no model
requests and needs no operator accounts, prompts or configuration. It does not
build, install or update the release pin.

## Inputs and invocation

Wait for splice-lead to supply the kept candidate carrying `15a0af554` and any
later fixes, its matching daemon jar, and their exact SHA-256 values. The runner
identifies the executed artifacts by those hashes. The required-behavior commit
is not a label for the candidate bytes or proof of their build revision. Do not run this
preparation before that handoff.

Requirements: Linux x86_64, bubblewrap, a reachable user systemd manager, Bun, and a Java
runtime appropriate for the supplied jar. The old pair must be the kept
bbe7a874c pair. The runner checks both old hashes and every supplied new artifact
hash before and after copying. It refuses an identical old/new front digest.
Use a new receipt directory; the runner refuses to overwrite a previous run.

The command has no bearer argument or credential-bearing address:

```sh
bun tools/hub-standin/qualify.ts \
  --old-pair /path/to/kept/bbe7a874c \
  --pair /path/to/kept/candidate \
  --jar /path/to/candidate/splice.jar \
  --java /absolute/path/to/java \
  --front-sha256 RELEASE_OWNER_FRONT_SHA256 \
  --holder-sha256 RELEASE_OWNER_HOLDER_SHA256 \
  --jar-sha256 RELEASE_OWNER_JAR_SHA256 \
  --reader-mode jvm-core \
  --receipt-dir /path/inside/project/artifacts/hub-qualification/candidate-RUN
```

The uppercase placeholders are artifact digests, not secrets. They must be
replaced with the release owner's complete values. No supplied new pair is
assumed by this document.

The default `jvm-core` mode is mandatory for final qualification. It invokes the
supplied jar's actual `McpToolResultStore` through `ReceiptReader.java`, including
argument-mismatch refusal. A release owner may first use `--reader-mode
native-json` with a hash-verified development daemon when native pins are needed
before the fresh JVM build. That stage checks actual native transport and whole
carrier JSON, reports `NATIVE_PAIR_ONLY`, and keeps core-reader acceptance
`PENDING` and source parity `UNQUALIFIED`. Rerun the same procedure with the exact
final jar and `jvm-core` mode to complete qualification.

## Checks and evidence

- **Missing MCP publication fails closed.** After proving the new front serves
  the desk's daemon-published MCP capability, withdraw
  only the desk's MCP publication, leaving management auth in place. Neither the
  previous MCP capability nor the management bearer may initialize through the
  native MCP front; both must return HTTP 401, the source-defined refusal. Management auth must still work on the isolated control
  plane, unchanged. Restore the publication before the recovery checks.
- **Daemon replacement preserves calls.** Start the new front under its held
  socket and keep counter calls and its GET notification stream open while replacing the isolated JVM
  daemon. Require no failed call, the same front and server PIDs, increasing
  counter state, uninterrupted stream and session, and a successful-call gap below the documented three-second
  bound. The counter instance identifier must also remain the same.
- **Changed-byte USR2 preserves calls.** Start the old front against a fresh
  private desk, and establish its session and GET stream first. Replace its executable with
  the supplied new front by sibling copy and atomic rename, then reload its own
  service with USR2. Do not restart the front for this step. Verify the running
  executable through `/proc/<owned-front-pid>/exe`, preserve its PID and counter
  instance, and require no failed call through the transition.

The changed-byte upgrade runs before daemon replacement. The procedure never
starts the old binary over state written by the new binary: an unsupported
downgrade would erase durable wire counters that the old version cannot read.

The peer also emits real raw MCP results and typed JSON-RPC errors under carried
`claudecode/toolUseId` metadata. The native carrier must preserve the exact
identity, arguments and complete typed packet, including structured data,
private metadata, falsy values and plain text that resembles JSON. Its filename
must be the callback SHA-256 and its mode 0600. These checks repeat after daemon
replacement and changed-byte exec.

A separate cold-front recovery check starts a delayed tool call, observes that
the actual peer started it, and checks the exact identity in the durable front
ledger. Only that private front is killed. Its replacement must capture the
delayed reply after recovery. The original HTTP connection is expected to close
in this separate crash check. New calls reuse the original MCP session without
initialize, and old carriers stay readable.

Each continuity step collects calls throughout the transition and at least a
hundred samples afterward. The counter also emits a stand-in notification; its
existing GET stream must receive data after each transition. The server's cgroup
must name the private holder slice. A dropped connection, RPC error, process replacement, nonincreasing
counter, ended notification stream, changed session, wrong running binary or
excessive gap fails the receipt. There is no
allowed-failure exemption for these two update checks. The previous proof's
front-restart check is not substituted for a changed-byte exec.

`receipt.json` records verdicts, exact artifact digests and the required behavior
reference. The release owner supplies the actual build revision separately. `calls.jsonl` contains only stand-in counts, PIDs, latency and sanitized
transport error codes. The daemon log comes only from the isolated daemon and is
redacted for the generated capabilities before retention.
`capture-proof.json`, `capture-peer.jsonl` and callback-hash JSON records retain
only synthetic fixture packets plus native/core-reader outcomes. Cold setup
initialize/list requests allow 15 seconds. Live calls keep their 2.5-second
deadline, each continuity step requires 100 successful samples and a gap
below three seconds, and its collection window is 30 seconds.

## Isolation and cleanup

The runner creates a private, short `/tmp/hq-*` home, with its own `.claude.json`,
minimal topology, state folder and wrap-guard directory. It copies supplied
artifacts there. It chooses free loopback ports and refuses the live control/MCP
ports. It creates uniquely named transient user units and slices; it never writes
or enables installed unit files. Child environments contain only the desk paths
and the systemd/runtime prerequisites, not operator authentication variables.

Bearer values are generated by the isolated daemon and read only inside the
runner. They are never passed in arguments, addresses, fragments or receipts.
The runner touches no live daemon, installed front, operator config or host
supervisor unit. The supplied daemon currently does not forward its configured
MCP slice to native holders. A desk-local `systemd-run` adapter therefore accepts
only the exact private holder/counter launch, forwards it to real systemd, and
uses the uniquely named desk slice with a bounded scope. A private bubblewrap
mount replaces the absolute `/usr/bin/systemd-run` only in the fixture daemon's
mount namespace; the adapter executes an independently hash-checked copy of the
real program. No installed binary or unit is replaced. It changes invocation
plumbing, not product source or installed units. An unexpected launch shape fails
closed. The runner retains the first daemon's user namespace by an owned open
descriptor and reuses it for the replacement daemon. Creating a sibling user
namespace would prevent Linux `/proc` process ownership checks from attesting
the retained holder. The runner checks namespace identity and requires a new JVM
PID. This adaptation is part of the receipt's method, not host qualification.
Cleanup targets only its own units, slices and child processes.
SIGINT and SIGTERM trigger the bounded cleanup path. If cleanup fails, the
receipt fails and the owned private desk is retained rather than deleting
resources still in use. A killed host or SIGKILL can still interrupt cleanup;
that is not host-recovery evidence.

## Explicit limits

- No new-source qualification is established until the supplied artifacts are
  actually run and a receipt passes. Preparation and the old receipt are not a
  new pass.
- The counter covers local stdio state and direct MCP HTTP calls. It does not
  exercise real Claude Code reconnection, plugin suppression, roots-dependent
  servers or browser/database sharing.
- Daemon replacement uses the same supplied jar in a new process. It does not
  establish daemon schema migration between different releases.
- Native-front refusal does not establish refusal on the JVM's direct MCP
  routes, whose management-auth behavior is a separate contract.
- Host recovery, production installation, live session recovery and management
  bearer rotation are not covered. No host action follows from a passing desk.
- The runner uses the new holder for the stand-in. It does not replace a running
  holder's binary or prove state handover between holder versions.

Sources: `docs/specs/mcp-hub.md` and the read-only qualification transcript
`agent-ac7f07f9cf5eb28bd.jsonl`. The new fail-closed check must match the native
front source carried by the release-owner artifacts.
