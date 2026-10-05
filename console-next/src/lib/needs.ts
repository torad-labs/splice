// The live Health row's fix, classified from the daemon's remedy rather than guessed from prose.
import type { FixKind } from '../types/doctor';
import type { Fix } from '../types/needs';
import { fixMasked, logsHrefOf } from './doctor';
import { S } from './words-needs';

const DOCTOR = '#/settings/health';
const open = (href: string, label: string): Fix => ({ kind: 'open', href, label });

/** A doctor row's one fix: the daemon runs it, or its command is copied (printed when masked), or,
 *  with no remedy in the row, Doctor is where to look. */
export function doctorFixOf(remedy: string | null, id: string | null, kind: FixKind | null): Fix {
  if (id !== null) return remedy === null ? { kind: 'doctor-fix', id } : { kind: 'doctor-fix', id, fallback: remedy };
  if (remedy === null) return open(DOCTOR, S.openDoctor);
  if (fixMasked(remedy)) return { kind: 'masked', command: remedy };
  // Two remedies the console serves itself, recognised whole: the daemon restart and a head's log tail.
  if (remedy.trim() === 'splice restart') return { kind: 'restart-daemon' };
  const logsHref = logsHrefOf(remedy);
  if (logsHref !== null) return { kind: 'open', href: logsHref, label: S.openLog, fallback: remedy };
  // Only what the daemon marked a command is offered to paste; advice is printed beside the link to Doctor.
  if (kind === 'command') return { kind: 'copy', command: remedy };
  return { kind: 'open', href: DOCTOR, label: S.openDoctor, fallback: remedy };
}
