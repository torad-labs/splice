// Hand-authored instrument primitives (.myx-*). No component library (locked).
// Every data-driven surface designs its full state cycle: loading skeletons
// shaped like the final layout, composed empty states, inline errors.
import './ui.css';

/* PANEL AND EMPTYSTATE WERE EXPORTED AND PLACED NOWHERE, AND ARE DELETED (M1-101).
   Panel had zero JSX sites in the whole console and no hand-rolled equivalent to replace: no file
   outside this one writes a `myx-panel*` class, so nothing was waiting for it. EmptyState had zero
   sites for a different and more interesting reason -- the console's honest empties are rendered by
   ITS OWN SIBLING, `Empty`, fifty-five times over, and `Empty` carries the `source` field the
   absence vocabulary needs (M1-20) while EmptyState was a bare `<p>` with a label. It did not lose
   to copy-paste; it lost to a better primitive that already existed.
   THREE INDEPENDENT CHECKS, because this campaign has a row that nearly deleted two finished
   features on one clean grep: (1) zero `<Panel` or `<EmptyState` JSX sites anywhere under
   console/src or console/tests, inside the library and outside it; (2) nothing outside this file NAMES
   either identifier; (3) every class token they carried -- myx-panel, -signal, -head, -title,
   -actions, -body and myx-empty -- appears in exactly two files, this one and ui.css, so removing
   both leaves no dangling class. The sheet rules went with them. */

/* SIX MORE OLD PRIMITIVES ARE DELETED (M3-04): StatusPill (with PillTone), Metric, ErrorNote,
   SkeletonRows, Stale and MeterBar. Their last consumers were widgets/head-plate and
   features/edit-config, which this row deletes -- so the orphan list could only be measured AFTER
   those went, and it was: re-grepped with both gone, each of the six has zero references under
   console/src and console/tests outside this file. Their sheet rules went with them; every class
   they carried was checked to appear in no other file. */

/* BTN, FIELD, WELL, CONFIRMBTN, STRIP AND STRIPFIELD ARE DELETED (console redesign, 2026-09-25).
   The first four had no JSX site left on feat/v0.4.0 either; Strip and StripField lost their last
   eighteen when every rack became a kit DataTable. Only the rules no live class shares went with
   them (.myx-field, .myx-well, the armed button, the strip's selected state, the field's basis):
   the strip and field classes still dress controls/blank.tsx, and `.myx-btn` is still written by
   hand on four pages. */

// The Strip Bay world (v0.4.0). One file per primitive; new code imports from here and never from
// a primitive's file.
export { HolderEdge } from './holder-edge';
export { Bay } from './bay';
export { ScopeInset } from './scope-inset';
export { FieldBox } from './field-box';
export { Reveal } from './reveal';
export { Empty } from './empty';
export { Figure } from './figure';
export { Badge, DataTable, DetailPanel, KeyValue, Meter, PageHeader, Section, Segmented, Stat, StatRow, Tally, weightedColumns } from './kit';
export type { Column, RowGroup, Tone } from './kit';
export { Braid, InfoTip, LayerChip, Legend, LifetimeBar, Pips, Ring, Sparkline, StackedBar, Tip, Waterfall } from './charts';
export type { BarPart, Mark, Strand, WaterfallStage } from './charts';
export { Lanes } from './lanes';
export type { Lane, LaneCard, LaneMessage } from './lanes';
export type { Provenance } from './field-box';
export type { Edge, Basis } from './types';
