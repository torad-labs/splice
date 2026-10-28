import type { CliScenario } from "./types";
import { mcpRpcErrorVectors } from "./mcp-rpc-cases";
import { mcpSearchScenario } from "./mcp-search-cases";

/** Fixed scripts exercise control/history independently of the frontends' different built-in tools. */
export const controlScenarios: readonly CliScenario[] = [
  { schema: 1, id: "control", actions: [
    { kind: "exec", source: 'store("core-marker","splice-parity-persistent-value"); text("splice-parity-stored");' },
    { kind: "exec", source: 'text(load("core-marker"));' },
    { kind: "exec", source: '// @exec: {"yield_time_ms": 1, "max_output_tokens": 1000}\ntext("splice-parity-prefix"); await new Promise(r=>setTimeout(r,180)); text("splice-parity-suffix");' },
    { kind: "wait" }, { kind: "wait" },
  ] },
  { schema: 1, id: "terminate", actions: [
    { kind: "exec", source: '// @exec: {"yield_time_ms": 1, "max_output_tokens": 1000}\ntext("before-termination"); await new Promise(r=>setTimeout(r,30000)); text("must-not-run-after-termination");' },
    { kind: "wait", terminate: true },
    { kind: "exec", source: 'text("new-cell-after-termination-healthy");' },
  ] },
  { schema: 1, id: "notify", actions: [
    { kind: "exec", source: 'notify("splice-parity-notification"); text("splice-parity-after-notification");' },
  ] },
  { schema: 1, id: "outer-batch", actions: [
    { kind: "exec", source: 'store("outer-batch","splice-parity-batch-value"); text("splice-parity-batch-first");', followingSources: ['text(load("outer-batch"));'] },
  ] },
  { schema: 1, id: "empty-yield", actions: [
    { kind: "exec", source: '// @exec: {"yield_time_ms": 1, "max_output_tokens": 1000}\nawait new Promise(r=>setTimeout(r,180)); text("splice-parity-empty-yield-suffix");' },
    { kind: "wait" },
  ] },
  { schema: 1, id: "silent", actions: [{ kind: "exec", source: '1+1;' }] },
];

/** MCP-specific results are reference observations, paired separately at the equivalent typed boundary. */
export const mcpScenarios: readonly CliScenario[] = [
  { schema: 1, id: "mcp", mcp: true, actions: [
    { kind: "exec", source: 'text({tag:"mcp-echo",value:await tools.mcp__parity__echo({value:"alpha"})});' },
    { kind: "exec", source: 'try{text({tag:"mcp-failure",resolved:await tools.mcp__parity__ordinary_failure({value:"beta"})});}catch(e){text({tag:"mcp-failure",rejected:String(e)});}' },
    { kind: "exec", source: 'text({tag:"mcp-structured",value:await tools.mcp__parity__structured({value:"gamma"})});' },
  ] },
  mcpSearchScenario(),
  { schema: 1, id: "mcp-rpc-error", mcp: true, actions: mcpRpcErrorVectors.map(vector => ({ kind: "exec",
    source: `try{text({tag:${JSON.stringify(vector.id)},resolved:await tools.mcp__parity__echo({value:${JSON.stringify(`rpc-error-probe/${vector.id}`)}})});}catch(e){text({tag:${JSON.stringify(vector.id)},rejected:String(e)});}`,
  })) },
];
