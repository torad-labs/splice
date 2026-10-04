// Code highlighting: highlight.js's core with the few languages a coding session shows. The only file
// that imports it, so swapping the highlighter is one file.
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import css from 'highlight.js/lib/languages/css';
import diff from 'highlight.js/lib/languages/diff';
import go from 'highlight.js/lib/languages/go';
import ini from 'highlight.js/lib/languages/ini';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import kotlin from 'highlight.js/lib/languages/kotlin';
import markdown from 'highlight.js/lib/languages/markdown';
import python from 'highlight.js/lib/languages/python';
import rust from 'highlight.js/lib/languages/rust';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

const LANGUAGES = { bash, css, diff, go, ini, java, javascript, json, kotlin, markdown, python, rust, sql, typescript, xml, yaml };
for (const [name, language] of Object.entries(LANGUAGES)) hljs.registerLanguage(name, language);

const ALIASES: Readonly<Record<string, string>> = {
  sh: 'bash', shell: 'bash', zsh: 'bash', js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
  py: 'python', rs: 'rust', kt: 'kotlin', html: 'xml', toml: 'ini', yml: 'yaml', md: 'markdown', patch: 'diff',
  mts: 'typescript', cts: 'typescript', mjs: 'javascript', cjs: 'javascript', kts: 'kotlin', svg: 'xml',
};

/** The grammar a language or a file extension names (`tsx` is typescript), or null when it is not one of ours. This map is the
 *  only one: a file's extension goes in as it is written and comes out a grammar here. */
export function languageName(language: string | null): string | null {
  if (language === null) return null;
  const name = ALIASES[language.toLowerCase()] ?? language.toLowerCase();
  return hljs.getLanguage(name) === undefined ? null : name;
}

/** Highlighted HTML for [code] in [language] (highlight.js escapes the text itself), or null when the
 *  language is not one of ours: an unknown language reads as plain text, never as a guess. */
export function highlight(code: string, language: string | null): string | null {
  const name = languageName(language);
  return name === null ? null : hljs.highlight(code, { language: name, ignoreIllegals: true }).value;
}
