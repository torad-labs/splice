// THE NOTICES WALL. THIRD_PARTY_NOTICES.md ships in every release, so every package and font the console-next bundle carries needs its notice
// there. The denominator is the bundle itself (tests/support/licenses.ts runs the production build in memory and reads the modules and font
// data it emitted), never package.json, so the transitive packages the markdown renderer and radix pull in are counted. The planted cases are
// the mutation proof: the check must fail, by name, on a package or font with no entry.
import { describe, expect, test } from 'vitest';
import { FONT_LICENSES, NOTICES_FILE, checkNotices, packageDirOf, packageLines, readBundle, readRepoFile, withSiblingCopyright } from './support/licenses';
import type { Bundle, BundledPackage } from './support/licenses';
import { readFileSync } from 'node:fs';

const bundle = await readBundle();
const notices = readFileSync(NOTICES_FILE, 'utf8');

/** Licenses a release may ship under without a person reading them first: a new one fails by name until it is added here on purpose. */
const ALLOWED = new Set(['MIT', 'ISC', 'BSD-3-Clause', 'BSD-2-Clause', '0BSD', 'Apache-2.0']);

const plant = (over: Partial<BundledPackage> = {}): BundledPackage => ({ name: 'planted-package', version: '9.9.9', license: 'MIT', copyright: ['Copyright (c) 2026 Nobody'], licenseFile: 'LICENSE', repository: null, author: null, ...over });

describe('the bundle', () => {
  test('is read from what the build emitted, transitive packages and all', () => {
    const names = new Set(bundle.packages.map((entry) => entry.name));
    console.log(`notices: ${bundle.packages.length} packages, ${bundle.fonts.length} font families, ${bundle.fontDataUris} embedded fonts`);
    for (const name of ['react', 'react-dom', '@radix-ui/react-dialog', '@tanstack/react-query', 'react-markdown', 'highlight.js', '@dnd-kit/core', 'react-router', 'remark-gfm']) expect(names, name).toContain(name);
    // Reached only through another package: no package.json line names these.
    for (const name of ['@floating-ui/dom', 'micromark', 'unified']) expect(names, name).toContain(name);
    expect(bundle.fonts.map((font) => font.family)).toEqual(['Fraunces', 'IBM Plex Mono', 'Source Serif 4']);
  });

  test('every package is under a license a release may ship under', () => {
    expect(bundle.packages.filter((entry) => !ALLOWED.has(entry.license)).map((entry) => `${entry.name} ${entry.version} ${entry.license}`)).toEqual([]);
  });
});

describe('THIRD_PARTY_NOTICES.md', () => {
  test('has an entry for every bundled package and font, none stale, and the font license texts beside the fonts', () => {
    expect(checkNotices(bundle, notices, readRepoFile)).toEqual([]);
  });

  test('every font family the bundle embeds has a recorded license', () => {
    expect(bundle.fonts.filter((font) => FONT_LICENSES[font.family] === undefined).map((font) => font.family)).toEqual([]);
  });
});

describe('the check itself', () => {
  test('a bundled package with no entry fails by name', () => {
    const planted: Bundle = { ...bundle, packages: [...bundle.packages, plant()] };
    expect(checkNotices(planted, notices, readRepoFile)).toEqual([{ name: 'planted-package 9.9.9', problem: 'no notice entry' }]);
  });

  test('a package whose LICENSE changed under its entry fails, and so does an entry nothing bundles any more', () => {
    const first = bundle.packages[0];
    if (first === undefined) throw new Error('the bundle is empty');
    const changed: Bundle = { ...bundle, packages: [{ ...first, copyright: ['Copyright (c) 2030 Someone Else'] }, ...bundle.packages.slice(1)] };
    expect(checkNotices(changed, notices, readRepoFile)).toEqual([{ name: `${first.name} ${first.version}`, problem: 'entry differs from its LICENSE' }]);
    const stale = notices.replace('<!-- console-next-bundle:end -->', `${packageLines(plant()).join('\n')}\n<!-- console-next-bundle:end -->`);
    expect(checkNotices(bundle, stale, readRepoFile)).toEqual([{ name: 'planted-package 9.9.9', problem: 'stale entry' }]);
  });

  test('a font with no entry, or no license text beside it, fails by name', () => {
    const planted: Bundle = { ...bundle, fonts: [...bundle.fonts, { family: 'Planted Sans', files: ['planted-400.woff2'] }], fontDataUris: bundle.fontDataUris + 1 };
    expect(checkNotices(planted, notices, readRepoFile)).toEqual([
      { name: 'Planted Sans', problem: 'font without notice' },
      { name: 'Planted Sans', problem: 'font license text missing' },
    ]);
    expect(checkNotices(bundle, notices, () => null).map((finding) => finding.problem)).toEqual(bundle.fonts.map(() => 'font license text missing'));
  });

  test('fonts the stylesheets name but the bundle did not embed, and a missing section, fail', () => {
    expect(checkNotices({ ...bundle, fontDataUris: bundle.fontDataUris - 1 }, notices, readRepoFile).map((finding) => finding.problem)).toEqual(['bundle and stylesheets disagree on fonts']);
    expect(checkNotices(bundle, '# nothing here', readRepoFile).map((finding) => finding.problem)).toEqual(['notices section missing', 'notices section missing']);
  });

  test('a release that ships no LICENSE file takes its copyright from a named source and says so', () => {
    const bare = plant({ copyright: [], licenseFile: null, repository: 'git+https://x/y.git', author: 'A. Person' });
    const kin = plant({ name: 'kin', copyright: ['Copyright (c) 2022 Kin'], repository: 'git+https://x/y.git' });
    expect(withSiblingCopyright([bare, kin], () => null)[0]?.copyright[0]).toContain('same repository');
    expect(withSiblingCopyright([{ ...bare, repository: null }], () => null)[0]?.copyright[0]).toContain('A. Person');
    expect(withSiblingCopyright([{ ...bare, repository: null, author: null }], () => null)[0]?.copyright).toEqual([]);
  });

  test('a module path names its package, scoped or not, at the last node_modules', () => {
    expect(packageDirOf('/r/node_modules/@a/b/lib/c.js?v=1')).toEqual({ name: '@a/b', dir: '/r/node_modules/@a/b' });
    expect(packageDirOf('/r/node_modules/x/node_modules/y/i.js')).toEqual({ name: 'y', dir: '/r/node_modules/x/node_modules/y' });
    expect(packageDirOf('/r/src/app.ts')).toBeNull();
  });
});
