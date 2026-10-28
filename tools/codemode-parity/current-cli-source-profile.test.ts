import { expect, test } from "bun:test";
import { reconcileSourceCliLock, sourceCliExportArgs, sourceCliPlans, sourceCliWitnessFindings } from "./current-cli-source-profile";
import { sha256 } from "./qualification";
import type { CurrentInventory } from "./current-inventory";
import type { CurrentReference } from "./current-readiness";

const source = "/immutable/source";
const metadata = { workspace_root: `${source}/codex-rs`, packages: [{ name: "codex-cli", version: "0.165.0", source: null, manifest_path: `${source}/codex-rs/cli/Cargo.toml` }] };
const versions = new Map([["codex-cli", "0.165.0"]]);
const original = `# immutable header
version = 4

[[package]]
name = "codex-cli"
version = "0.0.0"
dependencies = ["serde 1.0.0"]

[[package]]
name = "serde"
version = "1.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "external-checksum"
dependencies = ["serde_derive"]
`;

test("release reconciliation changes one exact local version line and preserves complete external bytes", () => {
  const repair = reconcileSourceCliLock(original, metadata, versions, source);
  expect(repair.derived).toBe(original.replace('name = "codex-cli"\nversion = "0.0.0"', 'name = "codex-cli"\nversion = "0.165.0"'));
  expect(repair.changed).toEqual([{ name: "codex-cli", from: "0.0.0", to: "0.165.0" }]);
  const before = (Bun.TOML.parse(original) as any).package[1], after = (Bun.TOML.parse(repair.derived) as any).package[1];
  expect(after).toEqual(before);
  expect(repair.derived.slice(repair.derived.indexOf('name = "serde"'))).toBe(original.slice(original.indexOf('name = "serde"')));
});

test("metadata cannot invent a different local release version", () => {
  const changed = structuredClone(metadata); changed.packages[0]!.version = "0.166.0";
  expect(() => reconcileSourceCliLock(original, changed, versions, source)).toThrow("official manifest");
});

test("a second local package cannot disappear from metadata", () => {
  const lock = original + '\n[[package]]\nname = "unreported"\nversion = "0.0.0"\n';
  expect(() => reconcileSourceCliLock(lock, metadata, versions, source)).toThrow("unrecognized local package");
});

test("an unrelated local dependency or duplicated package cannot enter the overlay", () => {
  const escaped = structuredClone(metadata); escaped.packages[0]!.manifest_path = "/other/tree/Cargo.toml";
  expect(() => reconcileSourceCliLock(original, escaped, versions, source)).toThrow("escaped");
  expect(() => reconcileSourceCliLock(original, { ...metadata, packages: [...metadata.packages, ...metadata.packages] }, versions, source)).toThrow("duplicate");
  expect(() => reconcileSourceCliLock(original.replace('version = "0.0.0"', 'version = "0.164.0"'), metadata, versions, source)).toThrow("placeholder");
});

test("mandatory source cases retain exact action bytes and typed MCP routes", () => {
  const plans = sourceCliPlans();
  for (const id of ["control", "terminate", "notify", "outer-batch", "empty-yield", "silent", "mcp", "mcp-search", "mcp-rpc-error"]) expect(plans.some(plan => plan.caseId === `cli-source/${id}`)).toBe(true);
  expect(new Set(plans.map(plan => plan.caseId)).size).toBe(plans.length);
  for (const plan of plans) {
    expect(plan.inputSha256).toBe(sha256(JSON.stringify(plan.scenario)));
    expect(plan.productionEntrypoint).toBe("splice.provider.codex.diagnostics.current.CodeModeGatewayScenarioProbe.run");
  }
  expect(plans.filter(plan => plan.scenario.mcp).every(plan => plan.scenario.actions.some(action => action.kind === "exec" && action.source.includes("tools.")))).toBe(true);
});

test("omitted or suite-name-only source evidence cannot satisfy any mandatory source case", async () => {
  const empty = {} as CurrentInventory, lock = {} as CurrentReference;
  const findings = await sourceCliWitnessFindings([], empty, "run", { path: "jar", sha256: "hash" }, lock);
  expect(findings.length).toBe(sourceCliPlans().length);
  const forged = { producer: "core-cli-llvm", caseId: "current.gateway-history" } as any;
  const rejected = await sourceCliWitnessFindings([forged], empty, "run", { path: "jar", sha256: "hash" }, lock);
  expect(rejected.some(finding => finding.includes("unrecognized actual-source CLI case"))).toBe(true);
  expect(rejected.filter(finding => finding.startsWith("mandatory actual-source CLI case was omitted:")).length).toBe(sourceCliPlans().length);
});

test("profile export retains all inventoried Rust files regardless of ownership or executable mapping", () => {
  const build = { binary: { path: "/frozen/codex" }, sourceDirectory: source, tools: { cov: { path: "/llvm/cov" } } } as any;
  const inventory = { files: [{ path: "codex-rs/core/src/tools/code_mode/mod.rs" }, { path: "codex-rs/analytics/src/lib.rs" }, { path: "codex-rs/code-mode-host/src/lib.rs" }, { path: "codex-rs/code-mode-protocol/src/grpc/session.proto" }], units: [] } as any;
  const args = sourceCliExportArgs(build, inventory, "/profile.profdata");
  for (const file of inventory.files.filter((file: { path: string }) => file.path.endsWith(".rs"))) expect(args).toContain(`${source}/${file.path}`);
  expect(args).not.toContain(`${source}/codex-rs/code-mode-protocol/src/grpc/session.proto`);
  expect(() => sourceCliExportArgs(build, { files: [] } as any, "/profile.profdata")).toThrow("no Rust source files");
});
