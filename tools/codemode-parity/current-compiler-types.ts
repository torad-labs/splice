import type { CompilerDef, CompilerSpan } from "./current-compiler-format";

export type CompilerNamespace = "type" | "value" | "macro" | "unknown";
export type CompilerDeclaration = { owner: string; parentOwner?: string; kind: string; span?: CompilerSpan; range?: CompilerRange };
export type CompilerRange = { file: string; start: number; end: number };
export type CompilerReference = {
  owner: string; span?: CompilerSpan; range?: CompilerRange; namespace: CompilerNamespace;
  kind: "path" | "import" | "typed-value" | "call" | "callee"; target?: string; resolution: string; fnSpan?: CompilerSpan;
};
export type CompilerRawIndex = { definitions: CompilerDef[]; declarations: CompilerDeclaration[]; references: CompilerReference[] };
export type CompilerArtifact = { path: string; sha256: string; bytes: number };
export type CompilerIrArtifact = CompilerArtifact & { encoding?: "gzip"; decoded?: { bytes: number; sha256: string } };
export type CompilerSourceIdentity = {
  revision: string; tree: string; originalCargoLockSha256: string; buildCargoLockSha256: string;
};
export type CompilerProof = {
  schema: 1; source: CompilerSourceIdentity; sourcePrefix: string;
  compiler: CompilerArtifact & { version: string };
  buildCargoLock: CompilerArtifact;
  crates: Array<{
    crate: string; cwd: string; features: string[]; cfgs: string[];
    externs: CompilerArtifact[];
    hir: CompilerIrArtifact & { args: string[] };
    thir: CompilerIrArtifact & { args: string[] };
  }>;
};
export type CompilerImmutableFile = { source: string; sha256: string };
export type CompilerExpectation = CompilerSourceIdentity & { compilerSha256: string; compilerVersion: string; proofSha256: string };
export type CompilerBindingIndex = CompilerRawIndex & {
  schema: 1; source: CompilerSourceIdentity; proofSha256: string; behavioralCoverage: false;
  omissions?: Array<{ scope: string; reason: string; resolution: string; count: number }>;
};
