import { isDeepStrictEqual } from 'node:util';

// Keep the fixture's existing HTTP byte ceiling; the declared 10,000-message workload
// fits within the structural limits without allowing unbounded JSON traversal.
export const requestBodyLimit = 32 * 1024 ** 2;
const maxDepth = 64, maxNodes = 65536;

function numberKey(token: string): string {
  const [mantissa = '', power = '0'] = token.toLowerCase().split('e');
  const negative = mantissa.startsWith('-'), unsigned = negative ? mantissa.slice(1) : mantissa;
  const point = unsigned.indexOf('.'), fraction = point < 0 ? 0 : unsigned.length - point - 1;
  const digits = unsigned.replace('.', '').replace(/^0+/, '');
  if (!digits) return negative ? '-0' : '0';
  const significant = digits.replace(/0+$/, ''), exponent = Number(power) - fraction + digits.length - significant.length;
  if (!Number.isSafeInteger(exponent)) throw new Error('request_number_invalid');
  return `${negative ? '-' : ''}${significant}e${exponent}`;
}

function parseBody(body: Uint8Array | string): unknown {
  const bytes = typeof body === 'string' ? Buffer.byteLength(body) : body.byteLength;
  if (!bytes || bytes > requestBodyLimit) throw new Error('request_byte_limit');
  const text = typeof body === 'string' ? body : new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(body);
  // Bound the original tokens before materializing the whole JSON value. The final
  // JSON.parse remains the grammar check; only individual member strings are decoded
  // here so escaped spellings cannot hide duplicates.
  const stack: { keys: Set<string> | null; member: boolean }[] = [];
  let index = 0, visited = 0, members = 0;
  const visit = () => {
    if (++visited > maxNodes) throw new Error('request_structure_limit');
  };
  while (index < text.length) {
    const char = text[index]!;
    if (' \t\r\n:'.includes(char)) { index++; continue; }
    if (char === '{' || char === '[') {
      if (stack.length >= maxDepth) throw new Error('request_structure_limit');
      visit(); stack.push({ keys: char === '{' ? new Set() : null, member: char === '{' }); index++; continue;
    }
    if (char === '}' || char === ']') { stack.pop(); index++; continue; }
    if (char === ',') {
      const frame = stack.at(-1); if (frame?.keys) frame.member = true;
      index++; continue;
    }
    if (char === '"') {
      const frame = stack.at(-1), member = frame?.member && frame.keys;
      // Even malformed objects without values cannot grow an unbounded key set
      // before the final grammar check. Valid member values also count as nodes.
      if (member) { if (++members > maxNodes) throw new Error('request_structure_limit'); }
      else visit();
      const start = index++;
      while (index < text.length && text[index] !== '"') index += text[index] === '\\' ? 2 : 1;
      if (index >= text.length) throw new Error('request_json_invalid');
      index++;
      if (member) {
        const key: string = JSON.parse(text.slice(start, index));
        if (member.has(key)) throw new Error('request_duplicate_member');
        member.add(key); frame!.member = false;
      }
    } else {
      visit();
      const start = index;
      while (index < text.length && !' \t\r\n,]}'.includes(text[index]!)) index++;
      if (char === '-' || (char >= '0' && char <= '9')) {
        const token = text.slice(start, index), number = Number(token);
        // Reject overflow and lossy parsing (for example 4.0000000000000001 -> 4).
        // Equivalent decimal/exponent spellings remain valid without changing a value.
        if (!Number.isFinite(number) || numberKey(token) !== numberKey(Object.is(number, -0) ? '-0' : String(number))) throw new Error('request_number_invalid');
      }
    }
  }
  return JSON.parse(text);
}

export function requestBodyMatcher(expected: Uint8Array | string): (body: Uint8Array | string) => boolean {
  const value = parseBody(expected);
  return body => {
    try { return isDeepStrictEqual(parseBody(body), value); }
    catch { return false; }
  };
}
