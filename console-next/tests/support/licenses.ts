// What console-next bundles and what THIRD_PARTY_NOTICES.md says about it. The denominator is the BUNDLE, not package.json: a vite
// production build is run in memory with a probe plugin, and every package that has code in the emitted chunks (and every font the
// stylesheets pull in) is read back from its own files: version and SPDX from its package.json, copyright lines from its own LICENSE.
// A list that came from package.json could not fail for the transitive packages react-markdown, radix or floating-ui pull in.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build, loadConfigFromFile } from 'vite';
import type { Plugin } from 'vite';

export const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const repoRoot = path.resolve(consoleRoot, '..');
export const NOTICES_FILE = path.join(repoRoot, 'THIRD_PARTY_NOTICES.md');
export const BEGIN = '<!-- console-next-bundle:begin -->';
export const END = '<!-- console-next-bundle:end -->';
export const FONT_BEGIN = '<!-- console-next-fonts:begin -->';
export const FONT_END = '<!-- console-next-fonts:end -->';

export interface BundledPackage {
  name: string;
  version: string;
  license: string;
  /** The copyright lines its own LICENSE file carries; empty when the file has none or there is no file. */
  copyright: string[];
  licenseFile: string | null;
  /** Where the package says its source lives, and who it names as author: the last resort for a release that ships no LICENSE file. */
  repository: string | null;
  author: string | null;
}

export interface BundledFont {
  family: string;
  /** The font files the stylesheets reference, by file name. */
  files: string[];
}

export interface Bundle {
  packages: BundledPackage[];
  fonts: BundledFont[];
  /** The font data URIs in the emitted page: the second, independent count of what shipped. */
  fontDataUris: number;
}

/** `/x/node_modules/@a/b/lib/c.js?v=1` names package `@a/b` under `/x/node_modules/@a/b`. Null for a file outside any node_modules. */
export function packageDirOf(id: string): { name: string; dir: string } | null {
  const clean = id.split('?')[0] ?? id;
  const marker = '/node_modules/';
  const at = clean.lastIndexOf(marker);
  if (at === -1) return null;
  const rest = clean.slice(at + marker.length).split('/');
  const name = rest[0]?.startsWith('@') ? rest.slice(0, 2).join('/') : rest[0] ?? '';
  return name === '' ? null : { name, dir: clean.slice(0, at + marker.length) + name };
}

const LICENSE_FILE = /^(licen[cs]e|copying)([.-].*)?$/i;
const COPYRIGHT_LINE = /^\s*(copyright\b(?!\s+(notice|holders?)\b)|\(c\)|©)/i;

export function packageEntry(name: string, dir: string): BundledPackage {
  const manifest = JSON.parse(readFileSync(path.join(dir, 'package.json'), 'utf8')) as { version?: string; license?: string | { type?: string }; repository?: string | { url?: string }; author?: string | { name?: string } };
  const declared = manifest.license;
  const licenseFile = readdirSync(dir).find((file) => LICENSE_FILE.test(file)) ?? null;
  const text = licenseFile === null ? '' : readFileSync(path.join(dir, licenseFile), 'utf8');
  const lines = text.split('\n').map((line) => line.trim()).filter((line) => COPYRIGHT_LINE.test(line));
  return {
    name,
    version: manifest.version ?? 'unknown',
    license: typeof declared === 'string' ? declared : declared?.type ?? 'UNKNOWN',
    copyright: [...new Set(lines)],
    licenseFile,
    repository: typeof manifest.repository === 'string' ? manifest.repository : manifest.repository?.url ?? null,
    author: typeof manifest.author === 'string' ? manifest.author : manifest.author?.name ?? null,
  };
}

/** One entry per package release: nested copies of the same release are one line. */
export function uniqueReleases(entries: readonly BundledPackage[]): BundledPackage[] {
  const seen = new Map<string, BundledPackage>();
  for (const entry of entries) {
    const key = `${entry.name} ${entry.version}`;
    const held = seen.get(key);
    if (held === undefined || (held.copyright.length === 0 && entry.copyright.length > 0)) seen.set(key, entry);
  }
  return [...seen.values()];
}

/** A release published without its LICENSE file takes, in order: the copyright lines of another release of the same package (a bundled one, else
 *  the one installed at the top of node_modules); those of another bundled package from the same repository; the author its own package.json
 *  names. The entry says which, so nothing here reads as more than the files say. */
export function withSiblingCopyright(entries: readonly BundledPackage[], installed: (name: string) => BundledPackage | null = installedRelease): BundledPackage[] {
  return entries.map((entry) => {
    if (entry.copyright.length > 0) return entry;
    const release = entries.find((other) => other.name === entry.name && other.copyright.length > 0) ?? installed(entry.name);
    if (release !== null && release !== undefined && release.copyright.length > 0) {
      return { ...entry, copyright: release.copyright.map((line) => `${line} (from the ${release.version} release's LICENSE; this release ships none)`) };
    }
    const kin = entry.repository === null ? undefined : entries.find((other) => other.repository === entry.repository && other.copyright.length > 0);
    if (kin !== undefined) return { ...entry, copyright: kin.copyright.map((line) => `${line} (from ${kin.name}'s LICENSE, the same repository; this package ships none)`) };
    return entry.author === null ? entry : { ...entry, copyright: [`Copyright (c) ${entry.author} (the author its package.json names; this package ships no LICENSE file)`] };
  });
}

function installedRelease(name: string): BundledPackage | null {
  const dir = path.join(repoRoot, 'node_modules', name);
  return existsSync(path.join(dir, 'package.json')) ? packageEntry(name, dir) : null;
}

/** Every `@font-face` block in a stylesheet with the family and the font file it points at. */
export function fontFaces(css: string): { family: string; file: string }[] {
  return [...css.matchAll(/@font-face\s*\{([^}]*)\}/g)].flatMap((block) => {
    const body = block[1] ?? '';
    const family = /font-family:\s*['"]?([^;'"]+)['"]?\s*;/.exec(body)?.[1]?.trim();
    const url = /url\(\s*['"]?([^)'"]+\.(?:woff2?|ttf|otf))['"]?\s*\)/.exec(body)?.[1];
    return family === undefined || url === undefined ? [] : [{ family, file: path.basename(url) }];
  });
}

/** Run the production build in memory and read what it bundled. */
export async function readBundle(): Promise<Bundle> {
  const dirs = new Map<string, string>(); // package directory -> name: two releases of one package are two entries
  const faces: { family: string; file: string }[] = [];
  let fontDataUris = 0;
  // Before the css plugin rewrites the urls, the stylesheets still say which font files they pull in.
  const stylesheets: Plugin = {
    name: 'licenses-probe-css',
    enforce: 'pre',
    transform(code, id) {
      if (id.split('?')[0]?.endsWith('.css') && !id.includes('/node_modules/')) faces.push(...fontFaces(code));
      return null;
    },
  };
  // After the css plugin has emitted the page's assets, the bundle is what shipped.
  const probe: Plugin = {
    name: 'licenses-probe',
    enforce: 'post',
    generateBundle(_options, bundle) {
      for (const output of Object.values(bundle)) {
        if (output.type === 'chunk') {
          for (const [id, module] of Object.entries(output.modules)) {
            const found = packageDirOf(id);
            if (found !== null && module.renderedLength > 0) dirs.set(found.dir, found.name);
          }
        } else {
          const source = typeof output.source === 'string' ? output.source : new TextDecoder().decode(output.source);
          fontDataUris += (source.match(/data:font\/woff2?;base64/g) ?? []).length;
        }
      }
    },
  };
  // The project's own config, less the plugin that folds the chunks into one page and deletes them from the bundle before anything can read them.
  const loaded = await loadConfigFromFile({ command: 'build', mode: 'production' }, path.join(consoleRoot, 'vite.config.ts'), consoleRoot);
  const own = (loaded?.config.plugins ?? []).flat(Infinity as 1).filter((plugin): plugin is Plugin => typeof plugin === 'object' && plugin !== null && 'name' in plugin && plugin.name !== 'vite:singlefile');
  // The release bundle is a production build whatever runs this: under vitest NODE_ENV is 'test', which resolves the packages' development
  // builds (debug, the dev variant of micromark's tables) and would read a different bundle than the one that ships.
  const before = process.env['NODE_ENV'];
  process.env['NODE_ENV'] = 'production';
  try {
    await build({ ...loaded?.config, configFile: false, mode: 'production', root: consoleRoot, logLevel: 'silent', plugins: [stylesheets, ...own, probe], build: { ...loaded?.config.build, write: false, outDir: path.join(consoleRoot, 'dist-notices-probe') } });
  } finally {
    if (before === undefined) delete process.env['NODE_ENV'];
    else process.env['NODE_ENV'] = before;
  }
  const byFamily = new Map<string, Set<string>>();
  for (const face of faces) byFamily.set(face.family, (byFamily.get(face.family) ?? new Set()).add(face.file));
  return {
    packages: withSiblingCopyright(uniqueReleases([...dirs].map(([dir, name]) => packageEntry(name, dir)))).sort((left, right) => left.name.localeCompare(right.name) || left.version.localeCompare(right.version)),
    fonts: [...byFamily].map(([family, files]) => ({ family, files: [...files].sort() })).sort((left, right) => left.family.localeCompare(right.family)),
    fontDataUris,
  };
}

// ── the notices ──────────────────────────────────────────────────────────────────────────────────

export const FONT_LICENSES: Readonly<Record<string, { copyright: string; textFile: string }>> = {
  Fraunces: { copyright: 'Copyright 2018 The Fraunces Project Authors (https://github.com/undercasetype/Fraunces)', textFile: 'console-next/src/styles/fonts/OFL-Fraunces.txt' },
  'Source Serif 4': { copyright: 'Copyright 2014 - 2023 Adobe (http://www.adobe.com/), with Reserved Font Name ‘Source’. All Rights Reserved.', textFile: 'console-next/src/styles/fonts/OFL-Source-Serif-4.txt' },
  'IBM Plex Mono': { copyright: 'Copyright 2019 IBM Corp. All rights reserved.', textFile: 'console-next/src/styles/fonts/OFL-IBM-Plex.txt' },
};

export function packageLines(entry: BundledPackage): string[] {
  const head = `- ${entry.name} ${entry.version} — ${entry.license}`;
  return [head, ...(entry.copyright.length === 0 ? ['  - (its LICENSE file carries no copyright line)'] : entry.copyright.map((line) => `  - ${line}`))];
}

export function fontLines(font: BundledFont): string[] {
  const known = FONT_LICENSES[font.family];
  return [
    `- ${font.family} (${font.files.map((file) => `\`${file}\``).join(', ')})`,
    `  - ${known?.copyright ?? '(no copyright recorded for this family)'}`,
    '  - License: SIL Open Font License, Version 1.1',
    `  - Full license text: \`${known?.textFile ?? '(none)'}\``,
  ];
}

export const renderPackages = (packages: readonly BundledPackage[]): string => packages.flatMap(packageLines).join('\n');
export const renderFonts = (fonts: readonly BundledFont[]): string => fonts.flatMap(fontLines).join('\n');

export function between(text: string, begin: string, end: string): string | null {
  const from = text.indexOf(begin);
  const to = text.indexOf(end);
  return from === -1 || to === -1 || to < from ? null : text.slice(from + begin.length, to).trim();
}

export type NoticeProblem = 'no notice entry' | 'entry differs from its LICENSE' | 'stale entry' | 'font without notice' | 'font license text missing' | 'bundle and stylesheets disagree on fonts' | 'notices section missing';
export interface NoticeFinding {
  name: string;
  problem: NoticeProblem;
}

/** Fails by name: a bundled package or font with no entry, an entry that no longer matches the package's own LICENSE, an entry for something no
 *  longer bundled, and a font whose license text is not beside it. `licenseText` reads a repo-relative path. */
export function checkNotices(bundle: Bundle, notices: string, licenseText: (relative: string) => string | null): NoticeFinding[] {
  const findings: NoticeFinding[] = [];
  const packages = between(notices, BEGIN, END);
  const fonts = between(notices, FONT_BEGIN, FONT_END);
  if (packages === null) findings.push({ name: BEGIN, problem: 'notices section missing' });
  if (fonts === null) findings.push({ name: FONT_BEGIN, problem: 'notices section missing' });
  if (packages !== null) {
    const heads = new Set([...packages.matchAll(/^- (\S+ \S+) — .+$/gm)].map((match) => match[1] ?? ''));
    for (const entry of bundle.packages) {
      const key = `${entry.name} ${entry.version}`;
      if (!heads.has(key)) findings.push({ name: key, problem: 'no notice entry' });
      else if (!packages.includes(packageLines(entry).join('\n'))) findings.push({ name: key, problem: 'entry differs from its LICENSE' });
    }
    const bundled = new Set(bundle.packages.map((entry) => `${entry.name} ${entry.version}`));
    for (const name of heads) if (!bundled.has(name)) findings.push({ name, problem: 'stale entry' });
  }
  if (fonts !== null) {
    for (const font of bundle.fonts) {
      if (!fonts.includes(fontLines(font).join('\n'))) findings.push({ name: font.family, problem: 'font without notice' });
      const textFile = FONT_LICENSES[font.family]?.textFile;
      const text = textFile === undefined ? null : licenseText(textFile);
      if (text === null || !/SIL OPEN FONT LICENSE/i.test(text)) findings.push({ name: font.family, problem: 'font license text missing' });
    }
  }
  const referenced = bundle.fonts.reduce((sum, font) => sum + font.files.length, 0);
  if (referenced !== bundle.fontDataUris) findings.push({ name: `${referenced} referenced, ${bundle.fontDataUris} embedded`, problem: 'bundle and stylesheets disagree on fonts' });
  return findings;
}

export const readRepoFile = (relative: string): string | null => (existsSync(path.join(repoRoot, relative)) ? readFileSync(path.join(repoRoot, relative), 'utf8') : null);
