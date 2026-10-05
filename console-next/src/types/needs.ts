/** The shared fix actions used by Settings' Health rows. */
export type Fix =
  | { kind: 'start'; head: string }
  | { kind: 'restart'; head: string }
  | { kind: 'restart-daemon' }
  | { kind: 'login'; head: string; label?: string }
  | { kind: 'copy'; command: string }
  /** A remedy the report's redaction reached: printed with why, never offered to copy. */
  | { kind: 'masked'; command: string }
  /** A fix the daemon runs itself, without a terminal command. */
  | { kind: 'doctor-fix'; id: string; fallback?: string }
  | { kind: 'open'; href: string; label: string; fallback?: string };
