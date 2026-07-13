#!/usr/bin/env node
// Terminal summary of ~/.claude-codex/claudex-compact-stats.jsonl (the carry
// of the v29 claudex-compact-stats reader; /mgmt/compact and the Compaction
// page read the same data live).
import { readCompactStats } from '../src/codex/compact.mjs';
import { statePaths } from '../src/config.mjs';

const stats = readCompactStats(10);
if (stats.total === 0) {
  console.log('no compact stats yet at', statePaths.compactStats());
  process.exit(0);
}
console.log('total', stats.total);
console.log('by_outcome', stats.by_outcome);
console.log('last_10', stats.tail);
