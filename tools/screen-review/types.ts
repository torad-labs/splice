// NEW: local screen-review proposals and feedback share one wire and disk contract.
export type Verdict = "pending" | "approved" | "declined";
export interface Screen {
  id: string;
  title: string;
  ready: boolean;
  why: string;
  value: string;
  dashboard: string;
}
export interface Pin {
  screen: string;
  id: string;
  x: number;
  y: number;
  width: number;
  text: string;
  at: string;
}
export interface ScreenFeedback {
  verdict: Verdict;
  at: string;
  pins: Pin[];
}
export interface Feedback {
  version: 1;
  screens: Record<string, ScreenFeedback>;
}
export type Mutation =
  | { screen: string; action: "verdict"; verdict: Verdict }
  | { screen: string; action: "pin"; id: string; x: number; y: number; width: number; text: string }
  | { screen: string; action: "delete-pin"; id: string };
