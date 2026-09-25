// Reading the keys a Kotlin view puts, so a console type is held to the serializer that writes its
// wire (tests/add-backend.test.ts, tests/add-model.test.ts). ONE reader because the const-single-source
// law moves a repeated key into a constant (`putJsonArray(MODELS)`), and a reader that knew only
// literals dropped that key from both tests at once.

/** Every key the member `fun <signature>` of [source] puts, nested blocks included: `put`,
 *  `putJsonArray` and `putJsonObject`, by literal or by one of the file's string constants. The body
 *  ends at the member's closing brace. A constant the file does not declare throws rather than drop
 *  a key, and so does a member the file does not have. */
export function keysPut(source: string, signature: string, file: string): string[] {
  const head = source.indexOf(`fun ${signature}`);
  if (head < 0) throw new Error(`no fun ${signature} in ${file}`);
  const body = source.slice(head, source.indexOf('\n    }', head));
  const constants = new Map([...source.matchAll(/const val ([A-Z_]+) = "([a-z_]+)"/g)]
    .flatMap(([, name, value]) => (name === undefined || value === undefined ? [] : [[name, value] as const])));
  const keys = [...body.matchAll(/put(?:JsonArray|JsonObject)?\((?:"([a-z_]+)"|([A-Z_]+)\b)/g)].map(([, literal, constant]) => {
    if (literal !== undefined) return literal;
    const value = constants.get(constant ?? '');
    if (value === undefined) throw new Error(`${file} puts ${constant ?? '?'}, which it declares no string for`);
    return value;
  });
  return [...new Set(keys)].sort();
}
