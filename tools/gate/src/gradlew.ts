#!/usr/bin/env bun
// NEW: the gradlew-named joint-admission command writes the holder after admission, then becomes the real wrapper.
import { writeFileSync } from "node:fs";
import { isoSeconds } from "./lib/slot.ts";

const { BUILDGATE_LOCK: lock, SPLICE_GRADLE_REAL: real, SPLICE_GRADLE_LABEL: label } = process.env;
if (!lock || !real || label === undefined) throw new Error("gradle-slot: missing joint admission envelope");
if (typeof process.execve !== "function") throw new Error("gradle-slot: Bun must support process.execve");
writeFileSync(`${lock}.holder`, `${label} pid=${process.pid} since=${isoSeconds(new Date())}\n`);
console.error(`gradle-slot: ${label} holds the slot — gradle busy`);
const env = { ...process.env };
delete env.SPLICE_GRADLE_REAL;
delete env.SPLICE_GRADLE_LABEL;
// THE MARKER IS SET BY THE PROCESS THAT BECOMES THE WRAPPER, not only by the gate that spawned it.
// The root wrapper re-executes itself through `gate slot` when it finds no live marker, and the exec
// below replaces THIS Bun with that wrapper in the SAME pid. Were the marker missing here, the wrapper
// would invoke Bun again inside a pid Bun has already run in — the one shape hostshield's handed-path
// guard refuses (reproduced by hostshield-lead 2026-10-10: a same-process execve onto `env bun` exits 1
// where an ordinary spawned child passes). It would refuse AFTER the slot was taken, so no build would
// run at all. The gate does set this (lib/slot.ts), but only a gate of this vintage does, and an older
// gate driving a newer checkout's wrapper is a real pairing in the pre-push trees. Set here it cannot
// be absent, so the wrapper never reaches for Bun in a pid that already held one.
env.SPLICE_GRADLE_SLOT = `${label}:${process.pid}`;
process.execve(real, [real, ...process.argv.slice(2)], env);
