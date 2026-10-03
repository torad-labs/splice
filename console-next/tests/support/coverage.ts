// Source readers for the tests that check the console's words against the daemon's Kotlin source.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
export const read = (relative: string): string => readFileSync(path.join(repoRoot, relative), 'utf8');

// Every tracked main Kotlin file (git's list, not a named file, so a declaration that moves is still found).
const KOTLIN_MAIN = /\/src\/main\/kotlin\/.+\.kt$/;
export const kotlinMain = execFileSync('git', ['ls-files', '*.kt'], { cwd: repoRoot, encoding: 'utf8' })
  .split('\n')
  .filter((file) => KOTLIN_MAIN.test(file));
