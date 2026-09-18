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

// ---- THE COLUMN WIDTHS, RE-DECLARED FROM WHAT THE COLUMNS ACTUALLY HOLD (M2-29) --------------
// Every number below was measured rack-wide at 1536 dark, each column taken as wide as its widest
// member -- label or value, whichever is wider -- with the element cloned into an unconstrained
// box, because a character count is not a width: the label face and the value face differ and a
// declared `ch` resolves against the CODE face's 0.6em advance (9.57px here, read from PORT's 7ch
// rendering at 67px). Measured need, in px including the cell's own 16px of padding:
//     provider 104   head 139   port 54   dialect 73   model 73
//     account 101    in flight 70   window 81   last turn 75     TOTAL 770px
// The rack declared about 1200px against that 770px. The gap was not padding and it was not
// caution: TWO of these columns were sized for SENTENCES THIS WIDGET CAN NO LONGER PRINT.
//
//   ABSENCE was 28ch, sized for "not reported by provider" -- but `windowText` returns
//   NOT_REPORTED, and NOT_REPORTED is the single word `unknown` (entities/account/model/derive.ts:19).
//   The sentence was retired into the absence vocabulary and the width stayed behind, so 269px of
//   rack was reserved for a string no code path can produce.
//
//   TURN was 19ch, sized for "no turn in flight" -- and the comment on NO_TURN below is the record
//   of that phrase being deliberately replaced by the word `none`, for the reason it states: a cell
//   that has to be read as a sentence is the defect the absence vocabulary exists to remove. Same
//   shape, same evening's work, and the width was not carried across either.
//
// THE RACK'S TOTAL IS NOT THE DEFECT -- ITS APPORTIONMENT IS, and a first cut of this row got that
// backwards. I reduced every column to its content (86ch, 823px) and measured the result: the rack
// ended at x=1047 inside a bay running to x=1500, leaving 453px of bare ground on every row, which
// is precisely the slab of bare paper M1-39 removed and M1-102 preserved. The cause is that a strip
// is `width: max-content` (the rule that lets .myx-bay-rows scroll it), so declared ch IS rendered
// width and flex-grow has no slack to distribute inside it. The surplus was never dead width: it is
// how the rack fills its bay, exactly as the comp's own 392px strips fill their 430px bays.
//
// So the budget stays and the SHARES move. Dividing each column's declared width by what it holds:
//     provider 1.29   head 1.17   port 1.24   dialect 1.44   model 1.44   account 1.04
//     in flight 0.96  window 3.31   last turn 2.42
// Every column sits between 1.0 and 1.5 except the two that were sized for sentences -- and
// `in flight` is at 0.96, already a hair NARROWER than what it prints. The numbers below are each
// column's content need scaled by the rack's own budget (1196px over 770px of content), so the
// rack is the same width it was and each column's share is what it actually carries.
const WIDE = 23;
/** Port is five characters, but this constant also carries `in flight`, whose `n/max` count plus
 *  its own label measures 70px against the 67px this declared -- the one column that was NARROWER
 *  than its content, not wider. Sized to the wider of its two users. */
const PORT = 11;
/** The account cell holds a masked id, which is nine characters plus an ellipsis: 101px, the one
 *  column already sized to its content. Unchanged. */
const ACCOUNT = 16;
/** Sized for `not built`, which is all these two cells can hold until V4-127 and V4-128 answer.
 *  Still sized to the placeholder rather than to the longest declared dialect name (21 characters),
 *  which remains a decision to revisit when those routes land -- this row only removed the slack
 *  between the placeholder's 73px and the 106px the column was declaring. */
const PLACEHOLDER = 12;
const NARROW = 17;
/** A window prints a percentage or the word `unknown` -- 81px of content, against the 268px this
 *  declared for a sentence no code path produces. The single worst-apportioned column in the rack.
 *
 *  No `basis` prop on this field: a measured window prints no basis anyway, and an ABSENT window
 *  already says so in its own value — passing `unavailable` appended a second word to the sentence
 *  and pushed it back into the ellipsis. */
const ABSENCE = 13;
/** A turn prints a time or the word `none` (see NO_TURN): 75px of content against the 182px this
 *  declared for `no turn in flight`, the phrase NO_TURN's own comment records retiring. */
const TURN = 12;

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
        <StripField w={NARROW} label={S.provider} value={providerText(head.authKind)} mono={false} />
      ) : null}
      {wanted.has('head') ? (
        <StripField w={WIDE} label={S.head} value={head.label} mono={false} />
      ) : null}
      {wanted.has('port') ? (
        <StripField w={PORT} label={S.port} value={head.port} />
      ) : null}
      {wanted.has('dialect') ? (
        <StripField w={PLACEHOLDER} label={S.dialect} value={dialect ?? S.notBuilt} mono={false} />
      ) : null}
      {wanted.has('model') ? (
        <StripField w={PLACEHOLDER} label={S.model} value={model ?? S.notBuilt} mono={false} />
      ) : null}
      {wanted.has('account') ? (
        <StripField w={ACCOUNT} label={S.account} value={account ?? S.none} mono={false} />
      ) : null}
      {wanted.has('inflight') ? (
        <StripField w={PORT} label={S.inflight} value={inflightText(head)} />
      ) : null}
      {wanted.has('window') ? (
        <StripField w={ABSENCE} label={S.window} value={windowText(window)} />

      ) : null}
      {wanted.has('turn') ? (
        <StripField w={TURN} label={S.turn} value={turn ?? NO_TURN} mono={false} />
      ) : null}
    </Strip>
  );
}
