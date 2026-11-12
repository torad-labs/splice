import { describe, expect, test } from "bun:test";
import { declarationContextReason } from "./current-declaration-context";
import type { CurrentInventory, SourceUnit } from "./current-inventory";

const revision = "360319d8dee62d87e2d59fc0c21805d79519cfed";
const path = "codex-rs/code-mode-protocol/src/session.rs";
const fileSha256 = "fe20ee15f845c03576a7d77cc88fc4f8c912403e8e42c2c2fe91ade01ffd5aac";
const provider: SourceUnit = {
  id: `${path}#trait_item@5567`, file: path, kind: "trait_item", label: "CodeModeSessionProvider",
  line: 180, start: 5567, end: 6509, ownership: "ported",
  sha256: "85c06a5328d7a6e78c1a9c2a96081226232e3501085676f6303f00350c917d17",
};
const availability: SourceUnit = {
  id: `${path}#function_item@5702`, file: path, kind: "function_item", label: "availability",
  line: 182, start: 5702, end: 5769, ownership: "ported",
  sha256: "22cbe667993d7671886cd6110c6f132f1035ee89e527ee9a1b5601501bf22890",
};
const limits: SourceUnit = {
  id: `${path}#function_item@6083`, file: path, kind: "function_item", label: "create_session_with_limits",
  line: 192, start: 6083, end: 6507, ownership: "ported",
  sha256: "a9c3b2480964004587d1f4a9ad47ecd6d4ce0c3b41789ff0b2665ccf4a29b69d",
};
const alias: SourceUnit = {
  id: `${path}#type_item@480`, file: path, kind: "type_item", label: "CodeModeSessionProviderFuture",
  line: 20, start: 480, end: 587, ownership: "ported",
  sha256: "2b260f7e5296ecd29f1913adfc1babd6c7fb53044eaf0699bb68d68350ffa5e0",
};
const signature: SourceUnit = {
  id: "codex-rs/core/src/tools/code_mode/response_adapter.rs#function_signature_item@280",
  file: "codex-rs/core/src/tools/code_mode/response_adapter.rs", kind: "function_signature_item", label: "into_protocol",
  line: 8, start: 280, end: 308, ownership: "ported",
  sha256: "c44b6366edcc4bc9536cee41a8abd5bfc464ecea2e09d3838bf3b482a9827320",
};

function fixture(units: SourceUnit[] = [provider, availability, limits, alias, signature]): CurrentInventory {
  return {
    schema: 2, generator: "test", sourceRevision: revision, sourceTree: "", sourceCargoLockSha256: "",
    astGrepVersion: "", discoveredCrates: [], denominatorSha256: "", grpcMethods: [], globalHelpers: [],
    units: structuredClone(units), files: [
      { path, sha256: fileSha256, gitBlob: "", discovery: "crate", units: 4 },
      { path: signature.file, sha256: "702b85e1a50baf24470f5018c355f27d7630134a2a9ea7b9a938c292702568f7", gitBlob: "", discovery: "module", units: 1 },
    ],
  };
}

describe("reviewed nonwire declarations", () => {
  test("the trait accounting container retains both executable default-body obligations", () => {
    const inventory = fixture();
    const reason = declarationContextReason(provider, inventory);
    expect(reason).toContain("receives no execution PASS");
    expect(reason).toContain(availability.id);
    expect(reason).toContain(limits.id);
    expect(declarationContextReason(availability, inventory)).toBeUndefined();
    expect(declarationContextReason(limits, inventory)).toBeUndefined();
  });

  test("dropping or changing a default body denies the enclosing trait context", () => {
    expect(declarationContextReason(provider, fixture([provider, availability]))).toBeUndefined();
    const inventory = fixture();
    inventory.units.find(unit => unit.id === limits.id)!.sha256 = "0".repeat(64);
    expect(declarationContextReason(provider, inventory)).toBeUndefined();
  });

  test("an additional executable body cannot hide beneath a reviewed trait", () => {
    const added = { ...availability, id: `${path}#function_item@5900`, start: 5900, end: 5990 };
    expect(declarationContextReason(provider, fixture([provider, availability, limits, added]))).toBeUndefined();
  });

  test("transparent future aliases and bodyless conversion signatures do not claim execution", () => {
    const inventory = fixture();
    expect(declarationContextReason(alias, inventory)).toContain("transparent Rust type alias");
    expect(declarationContextReason(signature, inventory)).toContain("bodyless Rust trait method signature");
    expect(declarationContextReason({ ...signature, kind: "function_item" }, inventory)).toBeUndefined();
  });

  test("a declaration with the same name does not inherit exact context", () => {
    const inventory = fixture();
    expect(declarationContextReason({ ...alias, id: `${path}#type_item@9000` }, inventory)).toBeUndefined();
    expect(declarationContextReason({ ...alias, sha256: "0".repeat(64) }, inventory)).toBeUndefined();
    expect(declarationContextReason({ ...alias, kind: "struct_item" }, inventory)).toBeUndefined();
  });

  test("source revisions, full file hashes, and census membership remain binding", () => {
    const stale = fixture();
    stale.sourceRevision = "0".repeat(40);
    expect(declarationContextReason(alias, stale)).toBeUndefined();
    const edited = fixture();
    edited.files[0]!.sha256 = "0".repeat(64);
    expect(declarationContextReason(alias, edited)).toBeUndefined();
    expect(declarationContextReason(alias, fixture([provider, availability, limits]))).toBeUndefined();
  });
});
