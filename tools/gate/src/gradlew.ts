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
process.execve(real, [real, ...process.argv.slice(2)], env);
