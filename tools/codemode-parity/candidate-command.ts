/** The existing application entry owns diagnostics; the qualifier adds no second JVM main. */
export function candidateCommand(java: string, jar: string, ...arguments_: string[]): string[] {
  return [java, "-cp", jar, "splice.app.MainKt", "--internal-code-mode-parity", ...arguments_];
}
