import assert from "node:assert/strict";
import { mcpFixtureNames, mcpFixtureTools } from "./mcp-contract";
import type { CliScenario, ObjectValue } from "./types";

export interface McpSearchProbe { action: number; tag: string; query: string; limit: number; storeSelected?: true }

/** Keep the original tied limit-one request and permission callback; add independent ranking/declaration observations. */
export const mcpSearchProbes: readonly McpSearchProbe[] = [
  { action: 0, tag: "ranked-search", query: "quasar-zephyr", limit: 1 },
  { action: 2, tag: "ranked-search-all", query: "quasar-zephyr", limit: 3 },
  { action: 3, tag: "ranked-search-echo", query: "echo", limit: 1 },
  { action: 4, tag: "ranked-search-failure", query: "ordinary_failure", limit: 1 },
  { action: 5, tag: "ranked-search-structured", query: "structured", limit: 1 },
  { action: 6, tag: "ranked-search-cutoff", query: "quasar-zephyr echo", limit: 2 },
  { action: 7, tag: "ranked-search-empty", query: "unmatchedpulsarfixture", limit: 1 },
  { action: 8, tag: "ranked-search-flat-name", query: "mcp__parityecho", limit: 1 },
  { action: 9, tag: "ranked-search-server", query: "parity", limit: 2 },
  { action: 10, tag: "ranked-search-property", query: "value", limit: 2 },
  { action: 11, tag: "ranked-search-selected", query: "quasar-zephyr", limit: 1, storeSelected: true },
];

export const mcpSearchSelectedAction = 12, mcpSearchEvidenceStart = 13;
export const mcpSearchSelectedArguments = { value: "selected-after-search" };

export function mcpSearchScenario(): CliScenario {
  const actions: CliScenario["actions"] = [{ kind: "exec", source: "" },
    { kind: "exec", source: 'text({tag:"discovered-call",value:await tools.mcp__parity__echo({value:"after-search"})});' }];
  for (const probe of mcpSearchProbes) {
    const search = `await tools.tool_search({query:${JSON.stringify(probe.query)},limit:${probe.limit}})`;
    actions[probe.action] = { kind: "exec", source: probe.storeSelected
      ? `const found=${search}; store("mcp-selected-name",found.tools[0].name); text({tag:${JSON.stringify(probe.tag)},value:found});`
      : `text({tag:${JSON.stringify(probe.tag)},value:${search}});` };
  }
  actions[mcpSearchSelectedAction] = { kind: "exec", source: `const name=load("mcp-selected-name"); text({tag:"mcp-selected-call",name,value:await tools[name](${JSON.stringify(mcpSearchSelectedArguments)})});` };
  mcpFixtureNames.forEach((name, index) => {
    const callable = `mcp__parity__${name}`;
    actions[mcpSearchEvidenceStart + index] = { kind: "exec", source: `text({tag:${JSON.stringify(`mcp-selected-evidence-${name}`)},name:${JSON.stringify(callable)},value:await tools.${callable}(${JSON.stringify(mcpSearchSelectedArguments)})});` };
  });
  return { schema: 1, id: "mcp-search", mcp: true, rankedSearch: true, actions };
}

/** tools::flat_tool_name concatenates namespace and member without adding the frontend's separator. */
export function mcpSearchCorpus(): string[] {
  return mcpFixtureTools().map(tool => `mcp__parity${tool.name} ${tool.name} ${tool.name} parity ${tool.description} value`);
}

// Re-derived with immutable bm25 2.3.2 over the exact source MCP metadata above. These values are checked against
// the real locked Rust scorer and the production Kotlin scorer in every mandatory current-core execution.
const shared = 0.26706287, unique = 1.5413032;
const ranks: Readonly<Record<string, ReadonlyArray<{ index: number; score: number }>>> = {
  "quasar-zephyr": mcpFixtureNames.map((_name, index) => ({ index, score: shared })),
  echo: [{ index: 0, score: unique }],
  ordinary_failure: [{ index: 1, score: unique }],
  structured: [{ index: 2, score: unique }],
  "quasar-zephyr echo": [{ index: 0, score: 1.8083661 }, { index: 1, score: shared }, { index: 2, score: shared }],
  unmatchedpulsarfixture: [],
  mcp__parityecho: [{ index: 0, score: 0.9808292 }],
  parity: mcpFixtureNames.map((_name, index) => ({ index, score: 0.13353144 })),
  value: mcpFixtureNames.map((_name, index) => ({ index, score: 0.13353144 })),
};

export function mcpSearchRankFixtures(): ObjectValue[] {
  return Object.keys(ranks).map(query => ({ operation: "rank", mcp_search_corpus: true, corpus: mcpSearchCorpus(), query }));
}

/** This checked matrix is a comparison precondition, never function-coverage credit or an invented source result. */
export function assertMcpSearchRank(fixture: Record<string, unknown>, result: unknown): void {
  if (fixture.mcp_search_corpus !== true) return;
  assert.equal(fixture.operation, "rank"); assert.deepEqual(fixture.corpus, mcpSearchCorpus());
  assert.ok(typeof fixture.query === "string" && Object.hasOwn(ranks, fixture.query), "Unreviewed MCP search scoring query");
  assert.equal(JSON.stringify(result), JSON.stringify({ matches: ranks[fixture.query] }), "Actual immutable Rust scorer changed the reviewed MCP search score matrix");
}

export function mcpSearchScores(query: string): Map<string, number> {
  assert.ok(Object.hasOwn(ranks, query), "Unreviewed MCP search query");
  return new Map(ranks[query]!.map(match => [`mcp__parity__${mcpFixtureNames[match.index]}`, match.score]));
}
