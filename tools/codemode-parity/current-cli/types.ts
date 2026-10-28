export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type ObjectValue = { [key: string]: Json };
export type AvailabilityToolMode = "direct" | "code_mode" | "code_mode_only";
export type NativeHostExpectation = { kind: "required" } | { kind: "disabled"; feature: "code_mode_host" };

export type CliAction =
  | { kind: "exec"; source: string; followingSources?: string[] }
  | { kind: "wait"; cellId?: string; yieldTimeMs?: number; terminate?: boolean };

export interface CliScenario {
  schema: 1;
  id: string;
  actions: CliAction[];
  rankedSearch?: boolean;
  mcp?: boolean;
  completionHoldMs?: number;
  applyPatch?: true;
  interruption?: { notification: string; resumePrompt: string; actionIndex?: number };
  availability?: { host: "disabled"; toolMode: AvailabilityToolMode };
}

export interface CliReference {
  cli: { path: string; sha256: string };
  host: { path: string; sha256: string };
  sourceRevision: string;
}

export interface NativeCompletion {
  sessionId: string;
  finalText: string;
  sourceFile: string;
}

export interface CliObservation {
  request: number;
  input: ObjectValue[];
}

export interface CliProcess {
  pid: number;
  sha256: string;
  startTicks: string;
  exit: number;
}

export interface CliRun {
  schema: 1;
  scenarioId: string;
  scenario: CliScenario;
  nonce: string;
  inputSha256: string;
  reference: CliReference;
  directory: string;
  completion: NativeCompletion;
  observations: CliObservation[];
  taskRequests: number;
  auxiliaryRequests: number;
  cliProcess: CliProcess;
  hostProcesses: Array<{ pid: number; sha256: string }>;
  freeformTool?: { binary: { path: string; sha256: string; bytes: number }; proof: { path: string; sha256: string; bytes: number } };
}
