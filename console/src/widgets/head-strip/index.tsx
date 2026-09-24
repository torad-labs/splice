// One head as a printed strip. The holder edge carries the attention state and its printed cause;
// the field grid carries what the daemon reports about the head.
//
// Provider family is a printed field AND a monochrome monogram, never a color: the brief is
// explicit that family is not an attention signal, and a colored family chip would compete with the
// holder edge for exactly the job the edge exists to do.
import { NOT_REPORTED } from '@entities/account';
import type { HeadStatus } from '@shared/api';
import {
  inflightText,
  liveTurnText,
  providerFamily,
  PROVIDER_MARK,
} from '@entities/heads';
import type { HeadAttention, ProviderFamily } from '@entities/heads';
import type { HeadWindow } from '@entities/usage';
import { Strip, StripField } from '@shared/ui';
import { S } from './strings';
import './head-strip.css';

/** The columns a saved view may name. */
export const HEAD_COLUMNS = [
  'provider', 'head', 'port', 'dialect', 'model', 'account', 'inflight', 'window', 'turn',
] as const;

export type HeadColumn = (typeof HEAD_COLUMNS)[number];

// ---- THE COLUMN WIDTHS, IN ch OF THE CODE FACE (0.6em) ------------------------------------------
// Each column is declared as wide as the longest value the live fleet prints in it, read off the
// operator's daemon (2026-09-23, console design pass): the dialect `anthropic-passthrough` and the
// model `anthropic/claude-haiku-4.5` were cut to `anthropic-p...` and `anthropic/cl...` while the
// window and last-turn columns, which print `unknown` and `none`, held twice their need. A value in
// the label face advances about 0.5em, so N characters need about 0.83N ch here. The rack fills its
// bay by sharing the slack in proportion to these numbers (StripField's flex-grow), so these are
// shares as much as floors.
const PROVIDER = 12;
const HEAD = 19;
const PORT = 6;
const DIALECT = 18;
const MODEL = 22;
/** A masked id is nine characters plus an ellipsis. */
const ACCOUNT = 11;
/** `n/max` in flight. */
const INFLIGHT = 7;
/** A window prints a percentage or the word `unknown`; an absent window says so in its own value,
 *  so the field carries no basis word beside it. */
const WINDOW = 9;
/** A turn prints a time or the word `none` (see NO_TURN). */
const TURN = 8;

/** The family as one printed field: the monogram, then the family's name. */
export function providerText(authKind: string): string {
  const family: ProviderFamily = providerFamily(authKind);
  return `${PROVIDER_MARK[family]} ${family}`;
}

/**
 * What a head with nothing in flight prints. Not a strings.ts value: this is a statement, not
 * chrome, and the label wall caps a table entry at three words (CONTRACTS.md section 4).
 */
/* NOT A PHRASE BUT A FACT THE VOCABULARY ALREADY NAMES (M1-69). This said `no turn in flight` -
   four words for "we asked which turn and the answer is none", which is exactly what `none` means
   in the vocabulary the tables carry. It is a cell value, not a sentence in an opened note, so it
   takes the word rather than the sentence: the eye scans a column of cells, and a cell that has to
   be read as a sentence is the defect the absence vocabulary exists to remove.
   Kept as a named constant so the call site reads the same. */
const NO_TURN = S.none;

/** A window as printed. Null means the head reports none, which is not a zero. */
export function windowText(head: HeadWindow): string {
  return head.pct === null ? NOT_REPORTED : `${head.pct}%`;
}

export function HeadStrip({ head, attention, window, account, dialect, model, columns, selected, onOpen }: {
  head: HeadStatus;
  attention: HeadAttention;
  window: HeadWindow;
  /** The account behind the head, or null when the auth card names none. */
  account: string | null;
  /** From the topology payload. Null while GET /api/topology is still a row (V4-128). */
  dialect: string | null;
  /** The head's pinned model. Null while GET /api/models is still a row (V4-127). */
  model: string | null;
  columns: readonly string[];
  selected?: boolean;
  onOpen?: () => void;
}) {
  // An empty list means every column, the same convention `columnsOf` uses: a view that never
  // touched its fields must not render a strip with no fields in it.
  const wanted = new Set(columns.length === 0 ? HEAD_COLUMNS : columns);
  const turn = liveTurnText(head);

  return (
    <Strip
      edge={attention.edge}
      edgeLabel={attention.label}
      cocked={attention.cocked}
      struck={attention.struck}
      selected={selected ?? false}
      {...(onOpen === undefined ? {} : { onOpen })}
      ariaLabel={`${head.label} ${head.authKind}`}
    >
      {wanted.has('provider') ? (
        <StripField w={PROVIDER} label={S.provider} value={providerText(head.authKind)} mono={false} />
      ) : null}
      {wanted.has('head') ? (
        <StripField w={HEAD} label={S.head} value={head.label} mono={false} />
      ) : null}
      {wanted.has('port') ? (
        <StripField w={PORT} label={S.port} value={head.port} />
      ) : null}
      {wanted.has('dialect') ? (
        <StripField w={DIALECT} label={S.dialect} value={dialect ?? S.none} mono={false} />
      ) : null}
      {wanted.has('model') ? (
        <StripField w={MODEL} label={S.model} value={model ?? S.none} mono={false} />
      ) : null}
      {wanted.has('account') ? (
        <StripField w={ACCOUNT} label={S.account} value={account ?? S.none} mono={false} />
      ) : null}
      {wanted.has('inflight') ? (
        <StripField w={INFLIGHT} label={S.inflight} value={inflightText(head)} />
      ) : null}
      {wanted.has('window') ? (
        <StripField w={WINDOW} label={S.window} value={windowText(window)} />

      ) : null}
      {wanted.has('turn') ? (
        <StripField w={TURN} label={S.turn} value={turn ?? NO_TURN} mono={false} />
      ) : null}
    </Strip>
  );
}
