// The coverage wall's denominator, parsed from the source of record at test
// time and never hand-listed. A hand list cannot fail for anything missing from
// itself, so the only honest denominator is the file the daemon itself reads.
//
// Parse methods (recorded on the M1-04 ledger note, printed by the wall):
//   knobs     gateway/.../config/Knob.kt      `^    [A-Z][A-Z0-9_]*\(`        45 today
//   topology  six files' `@SerialName("...")` values                          58 distinct today
//   routes    .dev/campaigns/web-console/FEATURES.md sections 2.1 and 6, first column,
//             backticked spans, normalized by the CONTRACTS.md section 4 rule  52 distinct today

/** Knob.kt: every enum entry, i.e. each `NAME(` at entry indentation. */
export function parseKnobNames(source: string): string[] {
  const names = [...source.matchAll(/^ {4}([A-Z][A-Z0-9_]*)\(/gm)].map((match) => match[1]);
  return [...new Set(names)].sort();
}

/** One topology source file: every `@SerialName("...")` value it declares. */
export function parseSerialNames(source: string): string[] {
  return [...source.matchAll(/@SerialName\("([^"]+)"\)/g)].map((match) => match[1]);
}

/** The first column of every table row in a section slice. */
function firstColumn(section: string): string[] {
  return section
    .split('\n')
    .filter((line) => line.startsWith('|'))
    .map((line) => line.split('|')[1]);
}

/** The lines of one section, from its heading to the next heading of its level. */
function sectionOf(markdown: string, heading: RegExp, stop: RegExp): string {
  const lines = markdown.split('\n');
  const from = lines.findIndex((line) => heading.test(line));
  if (from === -1) throw new Error(`FEATURES.md: no heading matching ${String(heading)}`);
  const to = lines.findIndex((line, index) => index > from && stop.test(line));
  return lines.slice(from, to === -1 ? lines.length : to).join('\n');
}

const ROUTE_SECTIONS: ReadonlyArray<{ heading: RegExp; stop: RegExp }> = [
  { heading: /^### 2\.1 /, stop: /^### / },
  { heading: /^## 6\. /, stop: /^## / },
];

/** Every backticked span of the route tables' first column, unnormalized. */
export function parseRouteSpans(markdown: string): string[] {
  const spans: string[] = [];
  for (const { heading, stop } of ROUTE_SECTIONS) {
    for (const cell of firstColumn(sectionOf(markdown, heading, stop))) {
      for (const match of cell.matchAll(/`([^`]+)`/g)) spans.push(match[1]);
    }
  }
  return spans;
}

const METHOD_PREFIX = /^[A-Z]+(?:\/[A-Z]+)*\s+/;

/** A `{a,b}` group expands to one item per alternate; `{head}` stays literal. */
function expandAlternates(path: string): string[] {
  const group = /\{([^{}]*,[^{}]*)\}/.exec(path);
  if (group === null) return [path];
  const [whole, inner] = [group[0], group[1]];
  const before = path.slice(0, group.index);
  const after = path.slice(group.index + whole.length);
  return inner.split(',').flatMap((alternate) => expandAlternates(`${before}${alternate}${after}`));
}

/**
 * CONTRACTS.md section 4, one rule: strip the method prefix and any `?query`,
 * expand `{a,b}` alternates, drop a span that does not start with `/`.
 *
 * `GET/PUT /api/teams` is "one item per method", and every method strips to the
 * same path, so the method prefix simply goes. The query is dropped BEFORE the
 * alternates expand: `?window=1h,24h,7d` is a query, not an alternation.
 */
export function normalizeRoute(span: string): string[] {
  const path = span.replace(METHOD_PREFIX, '').split('?')[0].trim();
  if (!path.startsWith('/')) return [];
  return expandAlternates(path);
}

export function parseRouteNames(markdown: string): string[] {
  return [...new Set(parseRouteSpans(markdown).flatMap(normalizeRoute))].sort();
}

/** The runtime knob enum — the denominator for `kind: 'knob'`. */
export const KNOB_SOURCE = 'core/src/main/kotlin/splice/core/config/Knob.kt';

/** The six topology sources named by M1-04; CompactionScope.kt declares no @SerialName. */
export const TOPOLOGY_SOURCES: readonly string[] = [
  'core/src/main/kotlin/splice/core/topology/Topology.kt',
  'core/src/main/kotlin/splice/core/topology/QuirksConfig.kt',
  'core/src/main/kotlin/splice/core/topology/TopologySchema.kt',
  'core/src/main/kotlin/splice/core/prompt/HeadSystemPrompt.kt',
  'core/src/main/kotlin/splice/core/model/TokenCost.kt',
  'core/src/main/kotlin/splice/core/compaction/CompactionScope.kt',
];

export const FEATURES_SOURCE = '.dev/campaigns/web-console/FEATURES.md';
