// The redaction check: does the doctor payload the console is about to render still carry a
// credential shape? FEATURES.md 4.12 wants the report "redacted like the CLI", and the CLI's rule is
// DoctorRedaction.text (app/.../cli/DoctorRedaction.kt:59-69). This mirrors its SHAPES in
// its ORDER, so the console's verdict agrees with the pass that produced the payload.
//
// The check reports WHERE a shape was found and WHICH shape, never the value: a leak reporter that
// echoed the matched text would be the leak it is looking for. Paths are JSON paths, and they are
// the whole output.
//
// WHAT IS NOT MIRRORED, and why: the CLI also masks paths that are not under its allowed prefixes
// (DoctorRedaction.kt:55-72). That allowlist is built at runtime from the daemon's own state, log,
// share and bin directories (DoctorRedaction.kt:35, the StatePaths/InstallPaths arguments), which
// the console cannot know; a mirror built from only the literal prefixes would flag splice's own
// state directory as foreign and make every report read as a leak. So the path rule stays the
// daemon's, and this check covers the credential and identity shapes, which are the actual secrets.
/** The credential and identifier shapes the CLI masks. */
export type LeakKind = 'jwt' | 'bearer' | 'key-value' | 'provider-key' | 'email' | 'uuid' | 'opaque';

/** Where a shape survived, named by kind and JSON path. The matched text is deliberately absent. */
export interface Leak {
  kind: LeakKind;
  where: string;
}

/**
 * What the CLI writes where it removed a bearer token or a key=value value (DoctorRedaction.kt:64-65).
 *
 * Its bearer and key=value shapes match their own output (`NAME_KEY=<redacted>` is still a key, a
 * separator and a value), so a mirror that did not know this mask refused every report the pass had
 * cleaned. It is matched EXACTLY: case-sensitive, and ending where the CLI's value run ended -- at
 * whitespace, a quote or the end of the text (the key=value run is `[^\s"']+`, the bearer run `\S+`).
 * `<REDACTED>` or `<redacted>tail` is not the CLI's mask, and still reads as a leak.
 */
const DAEMON_MASK = '<redacted>';
const MASK_END = /^(?:[\s"']|$)/;

function isDaemonMask(text: string, at: number): boolean {
  return text.startsWith(DAEMON_MASK, at) && MASK_END.test(text.slice(at + DAEMON_MASK.length));
}

/** One shape per entry, in the CLI's own order of application: JWT before bearer, the key=value
 *  families before the provider prefixes, key-shaped identifiers before the generic opaque run.
 *  `masked` marks the two shapes the CLI replaces with [DAEMON_MASK]; their pattern captures the
 *  value as its last group, so a match can be told apart from the CLI's own work. */
const SHAPES: readonly { kind: LeakKind; pattern: RegExp; masked?: true }[] = [
  { kind: 'jwt', pattern: /eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}/ },
  { kind: 'bearer', pattern: /\bbearer\s+(\S+)/gi, masked: true },
  {
    kind: 'key-value',
    pattern: /\b[a-z0-9_-]*(?:key|token|secret|password|passwd|pwd|cookie|signature|credential|authorization)[a-z0-9_-]*"?\s*[=:]\s*"?(\S+)/gi,
    masked: true,
  },
  { kind: 'provider-key', pattern: /\b(sk|xai|gsk|xoxb|ghp|github_pat)[-_][A-Za-z0-9_-]{8,}/ },
  { kind: 'email', pattern: /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/ },
  { kind: 'uuid', pattern: /\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b/ },
  { kind: 'opaque', pattern: /\b[A-Za-z0-9_-]{40,}\b/ },
];

/**
 * Whether a masked shape matches anything in [text] that is not the CLI's own mask.
 *
 * Every match is looked at, not only the first: `A_KEY=<redacted> B_TOKEN=hunter2` carries a real
 * value beside the mask. After a mask the scan resumes right past it rather than past the whole
 * match, because a value run is greedy and a second pair written flush against the mask would
 * otherwise ride inside the match that was excused.
 */
function unmasked(pattern: RegExp, text: string): boolean {
  pattern.lastIndex = 0;
  for (let match = pattern.exec(text); match !== null; match = pattern.exec(text)) {
    const value = match[match.length - 1] ?? '';
    const at = match.index + match[0].length - value.length;
    if (!isDaemonMask(text, at)) return true;
    pattern.lastIndex = at + DAEMON_MASK.length;
  }
  return false;
}

/**
 * Every shape found in [text], in the CLI's order and at most one per shape.
 *
 * The key=value shape needs its key, so a bare secret with no key in front of it is caught by the
 * opaque run instead, exactly as in the CLI. A `key=value` whose value is a number or a short safe
 * token still matches the shape: the CLI masks it too, which is why a check that finds one is
 * evidence the pass did not run rather than a false alarm. The one value that is not a leak is the
 * CLI's own [DAEMON_MASK].
 */
export function leaksInText(text: string): LeakKind[] {
  const found: LeakKind[] = [];
  for (const shape of SHAPES) {
    if (shape.masked === true ? unmasked(shape.pattern, text) : shape.pattern.test(text)) found.push(shape.kind);
  }
  return found;
}

function leaksAt(value: unknown, path: string, out: Leak[]): void {
  if (typeof value === 'string') {
    for (const kind of leaksInText(value)) out.push({ kind, where: path });
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((entry, index) => leaksAt(entry, `${path}[${index}]`, out));
    return;
  }
  if (typeof value === 'object' && value !== null) {
    for (const [key, entry] of Object.entries(value)) {
      leaksAt(entry, path === '' ? key : `${path}.${key}`, out);
    }
  }
  // Numbers, booleans and null carry no shape to match.
}

/** Every leak in a payload, each with the JSON path it sits at. Empty means the payload is clean. */
export function leaksIn(value: unknown): Leak[] {
  const out: Leak[] = [];
  leaksAt(value, '', out);
  return out;
}

/**
 * Whether the payload carries no shape at all. A page should gate on THIS and not on the array: an
 * empty array is truthy, so `if (leaksIn(x))` would refuse to render every clean report.
 */
export function isRedacted(value: unknown): boolean {
  return leaksIn(value).length === 0;
}
