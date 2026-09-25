// Reading server-rendered markup in a test: a fragment's text, a labelled table's names, rows and
// cells, and a kit Stat's figure by its label. ONE module because five page tests each carried a copy
// of the table reader, three of them stripping tags with a one-pass `/<[^>]*>/g` replace, which
// CodeQL flags as incomplete sanitisation (#264, alerts 11-13). These read text; they sanitise
// nothing. Text is taken by a character scan that skips each tag whole, a quoted attribute value
// included, and then decodes the five entities React escapes, in one pass.

const DECODED: Record<string, string> = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#x27;': "'" };
const ENCODED: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#x27;' };

/** Text as React writes it into markup: the five characters it escapes, in one pass. */
export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (ch) => ENCODED[ch] ?? ch);
}

function decode(text: string): string {
  return text.replace(/&(?:amp|lt|gt|quot|#x27);/g, (entity) => DECODED[entity] ?? entity);
}

/** The text a markup fragment shows: every character outside a tag, entities decoded. A `>` inside
 *  a quoted attribute value does not end its tag. */
export function cellText(html: string): string {
  let out = '';
  let inTag = false;
  let quote: string | null = null;
  for (const ch of html) {
    if (!inTag) {
      if (ch === '<') inTag = true;
      else out += ch;
    } else if (quote !== null) {
      if (ch === quote) quote = null;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
    } else if (ch === '>') {
      inTag = false;
    }
  }
  return decode(out);
}

export interface Table {
  /** The column names, in order. */
  names: string[];
  /** The body rows as markup, group title rows left out. */
  rows: string[];
  /** The group title rows as markup. */
  groups: string[];
  /** Each body row's cells as text, one list per entry of `rows`. */
  cells: string[][];
}

/** One labelled table. An absent table reads as empty, so a test fails on what it expected. */
export function tableOf(html: string, label: string): Table {
  const name = escapeHtml(label).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const inner = new RegExp(`<table[^>]*aria-label="${name}"[^>]*>([\\s\\S]*?)</table>`).exec(html)?.[1] ?? '';
  const [head = '', body = ''] = inner.split('</thead>');
  const all = body.split('<tr').slice(1);
  const rows = all.filter((row) => !row.includes('myx-dt-group'));
  return {
    names: [...head.matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((match) => decode(match[1] ?? '')),
    rows,
    groups: all.filter((row) => row.includes('myx-dt-group')),
    cells: rows.map((row) => row.split('<td').slice(1).map((cell) => cellText(`<td${cell}`))),
  };
}

/** A kit Stat's printed figure and caption, found by its label; null when no stat carries it. */
export function statOf(html: string, label: string): { value: string; sub: string | null } | null {
  const marker = `<p class="myx-stat-label">${escapeHtml(label)}</p>`;
  const start = html.indexOf(marker);
  if (start < 0) return null;
  const next = html.indexOf('<p class="myx-stat-label">', start + marker.length);
  const stat = html.slice(start, next < 0 ? undefined : next);
  const value = /<p class="myx-stat-value">([\s\S]*?)<\/p>/.exec(stat)?.[1] ?? '';
  const sub = /<p class="myx-stat-sub">([\s\S]*?)<\/p>/.exec(stat)?.[1];
  return { value: cellText(value), sub: sub === undefined ? null : cellText(sub) };
}
