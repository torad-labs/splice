// The world's controls: the seven things a page needs that are not a printed readout.
//
// The operator's ruling of 2026-09-18 traced the console reading as the old Torad plate system to
// this gap: CONTRACTS.md section 2 had no button, no text input, no error note and no loading
// state, so every page imported the old primitives from @shared/ui and with them the old paper,
// ink, vermilion and faces. These compose shared/ui and use CONTRACTS.md section 1 tokens only, so
// a page that needs to be pressed, typed into, chosen from, waited on or told about a failure has
// somewhere else to go. Choice and Flag were the last two missing (m1 design review D6): without
// them the Logs page rendered four native `<select>`s and the log tail a system-blue checkbox.
//
// WHAT IS DELIBERATELY NOT HERE: a dialog, a menu, a tooltip, a toast, a spinner. The world says
// confirmation is inline, attention is a holder edge, and absence is a printed word.
import './controls.css';

export { Key } from './key';
export type { KeyVariant } from './key';
export { Confirm, ConfirmKeys } from './confirm';
export { Input } from './input';
export { Choice, ChoiceList } from './choice';
export type { ChoiceOption } from './choice';
export { Flag } from './flag';
export { Blank } from './blank';
export { Fault } from './fault';
export { S as CONTROL_LABELS } from './strings';
