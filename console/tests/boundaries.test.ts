// BOUNDARY WALL (row V4-202). eslint.config.mjs is the architecture: FSD layers
// one way, slices through their index door, the HTTP client only in entity api
// segments. `bun run lint` passing proves the tree obeys it, and nothing about
// whether it still bites: a rule that stops matching reports zero findings, which
// is also what a clean tree reports. eslint-plugin-boundaries v7 documents exactly
// that trap (a deprecated rule left without its policies "never does anything").
//
// So each law is asked here with a planted import, through the real config and
// the real resolver, and must answer both ways: the forbidden import is refused
// BY a boundaries rule, and its lawful twin is not. Nothing is written into src/:
// lintText lints the source AS IF it lived at the path given.
//
// The last test holds the plugin's own deprecation notices at zero. They print to
// the console, not as lint findings, so `eslint` alone passes with them present.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';
import { afterAll, beforeAll, describe, expect, test, vi } from 'vitest';

const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// [law, planted file, source, refused?]
const PLANTS: [string, string, string, boolean][] = [
  ['shared never imports up into entities', 'src/shared/lib/plant.ts', "export { x } from '@entities/events';", true],
  ['a relative path is held to the same law', 'src/shared/lib/plant.ts', "export { x } from '../../entities/events';", true],
  ['a feature never imports a widget', 'src/features/theme/plant.ts', "export { x } from '@widgets/rule';", true],
  ['a feature never imports another feature', 'src/features/theme/plant.ts', "export { x } from '@features/palette';", true],
  ['an entity never imports another entity', 'src/entities/alert/plant.ts', "export { x } from '@entities/events';", true],
  ['a page imports an entity through its door', 'src/pages/fleet/plant.ts', "export { x } from '@entities/events';", false],
  ['a page never reaches past an entity door', 'src/pages/fleet/plant.ts', "export { x } from '@entities/events/model/store';", true],
  ['view code never holds the HTTP client', 'src/pages/fleet/plant.ts', "import * as api from '@shared/api';\nexport const y = api;", true],
  ['view code may name its payload types', 'src/pages/fleet/plant.ts', "export type { X } from '@shared/api';", false],
  ['an entity never holds the HTTP client outside api', 'src/entities/events/plant.ts', "import * as api from '@shared/api';\nexport const y = api;", true],
  ['an api segment holds the HTTP client', 'src/entities/alert/api/plant.ts', "import * as api from '@shared/api';\nexport const y = api;", false],
  ['an entity reads its own api segment', 'src/entities/events/plant.ts', "export { x } from '@entities/events/api';", false],
  ["an entity never reads another slice's api", 'src/entities/events/plant.ts', "export { x } from '@entities/alert/api';", true],
  ["an api segment never reads another slice's model", 'src/entities/alert/api/plant.ts', "export { x } from '@entities/events';", true],
  ['shared never reaches past a shared door', 'src/shared/lib/plant.ts', "export { x } from '@shared/ui/bay';", true],
  ['a file at src/ belongs to no layer', 'src/plant.ts', 'export const y = 1;', true],
  ['a script beside the shared stylesheets belongs to no layer', 'src/shared/plant.ts', 'export const y = 1;', true],
  ['a file outside src/ is no dependency', 'src/pages/fleet/plant.ts', "export { default } from '../../../playwright.config';", true],
];

const eslint = new ESLint({ cwd: consoleRoot });
const notices: string[] = [];

async function boundaryFindings(file: string, source: string): Promise<string[]> {
  const [result] = await eslint.lintText(`${source}\n`, { filePath: path.join(consoleRoot, file) });
  return result.messages
    .filter((m) => m.severity === 2 && m.ruleId?.startsWith('boundaries/'))
    .map((m) => `${m.ruleId}: ${m.message}`);
}

describe('the boundaries config refuses what it forbids and admits its lawful twin', () => {
  beforeAll(() => {
    vi.spyOn(console, 'warn').mockImplementation((...args: unknown[]) => {
      notices.push(args.join(' '));
    });
  });
  afterAll(() => {
    vi.restoreAllMocks();
  });

  for (const [law, file, source, refused] of PLANTS) {
    test(law, async () => {
      const findings = await boundaryFindings(file, source);
      if (refused) expect(findings, `${file} was admitted`).not.toEqual([]);
      else expect(findings).toEqual([]);
    });
  }

  test('the plugin prints no deprecation notice for this config', () => {
    expect(notices.filter((n) => n.includes('[boundaries]'))).toEqual([]);
  });
});
