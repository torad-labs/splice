/** A word the shell reads as itself, else single-quoted: a copied line runs exactly the argv the daemon
 *  answered, whatever a head's command is called. */
export function shellWord(word: string): string {
  return /^[\w./=-]+$/.test(word) ? word : `'${word.replaceAll("'", `'\\''`)}'`;
}

export const shellLine = (argv: readonly string[]): string => argv.map(shellWord).join(' ');
