import type { CaseId } from "./qualification";

// Only concrete executable drivers belong here. A trace file, assertion string,
// source grep, benchmark mean, or a test runner's green exit is not a driver.
export const verifiers: Partial<Record<CaseId, string>> = Object.fromEntries([
  "runtime.module-semantics", "runtime.sequential-callbacks", "runtime.partial-parallel-results",
  "runtime.explicit-yield", "runtime.timed-yield", "runtime.wait-suffix", "runtime.terminate",
  "runtime.notifications", "runtime.timers", "runtime.session-store",
  "runtime.errors-and-cancellation",
].map(id => [id, `codemode-parity/native-v1/${id}`]));
verifiers["evidence.failing-baseline"] = "codemode-parity/frozen-legacy-explicit-yield";
verifiers["packaging.native-host"] = "codemode-parity/bundled-native-inventory-and-live-process";
verifiers["runtime.output-content"] = "codemode-parity/native-host-rust-core-and-provider-media";
verifiers["evidence.installed-identity"] = "codemode-parity/installed-daemon-open-jar-and-owned-native-exe";
for (const id of ["runtime.item-boundary", "history.restart", "delivery.readable-reasoning", "delivery.interactive-cadence", "delivery.late-failure-recovery"] as const) {
  verifiers[id] = `codemode-parity/real-interactive-client/${id}`;
}
for (const id of ["history.append-only", "history.replay-is-not-steering", "history.cleared-results", "history.real-steering"] as const) {
  verifiers[id] = `codemode-parity/production-history/${id}`;
}

export const missingDimensions: Partial<Record<CaseId, string>> = {
  "runtime.item-boundary": "Needs gateway Responses item-completion admission and late-error integration driver.",
  "runtime.output-content": "Typed media runs against the native host; native-core output limit rendering requires its own driver.",
  "runtime.errors-and-cancellation": "Needs distinct tool/source/cancellation/host-loss observations with no repeated accepted effects.",
  "history.append-only": "Needs captured authoritative model requests through the production gateway.",
  "history.restart": "Needs real daemon restart with durable model history and callback ownership restoration.",
  "history.replay-is-not-steering": "Needs the captured callback-only replay driven through the gateway.",
  "history.real-steering": "Needs genuine new input, compaction, rewind, and edited-result driver cases.",
  "history.cleared-results": "Needs verified client-cleared result fixtures through the production gateway.",
  "delivery.readable-reasoning": "Needs captured client SSE and authoritative readable/encrypted reasoning items.",
  "delivery.interactive-cadence": "Needs actual interactive client captures with item/callback/stop/client timestamps.",
  "delivery.late-failure-recovery": "Needs actual client recovery and restart with exactly one external effect.",
  "packaging.native-host": "Needs packaged resolver negative probes without accidental local installation fallback.",
  "evidence.failing-baseline": "Needs frozen baseline executable run against cases fixed by the exact candidate.",
  "evidence.installed-identity": "Needs running daemon open jar and native child executable hashes matching qualified artifacts.",
};
