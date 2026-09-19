// The raw-topology view and its diff, behind the page's Reveal.
//
// WHY THE EDITOR DOES NOT WRITE. `splice.toml` is written by the daemon's structured TOML writer
// (`add-model`'s), which preserves the operator's comments and layout; there is no TOML parser in
// this bundle (there is no @codemirror/lang-toml), so re-parsing edited text here would either
// refuse half the file or silently drop the comments it did not round-trip. The FORMS are the
// write path, and they PUT the parsed object through that writer. What this editor is for is the
// other half of the same question: seeing the whole file at once, and seeing exactly what a draft
// would change before it is written.
//
// Both views mount in an effect, never during render, so a static render of the page (the tests)
// touches no DOM.
import { useEffect, useRef } from 'react';
import { StreamLanguage } from '@codemirror/language';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { MergeView } from '@codemirror/merge';
import { toml } from '@codemirror/legacy-modes/mode/toml';
import { S } from './strings';
import './toml-editor.css';

const tomlLanguage = () => StreamLanguage.define(toml);

/** The topology as text. Read-only unless the caller brings an onChange. */
export function TomlEditor({ text, ariaLabel = S.editor, onChange }: {
  text: string;
  ariaLabel?: string;
  onChange?: (next: string) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const listener = useRef(onChange);
  listener.current = onChange;

  useEffect(() => {
    const parent = host.current;
    if (parent === null) return;
    const created = new EditorView({
      parent,
      state: EditorState.create({
        doc: text,
        extensions: [
          tomlLanguage(),
          EditorView.lineWrapping,
          EditorView.contentAttributes.of({ 'aria-label': ariaLabel, role: 'textbox' }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) listener.current?.(update.state.doc.toString());
          }),
        ],
      }),
    });
    view.current = created;
    return () => {
      created.destroy();
      view.current = null;
    };
    // Mounted once: the doc is synced from the prop in the effect below, so
    // recreating the editor on every change would fight the operator for the
    // cursor. `text` and `ariaLabel` are read at mount and synced after.
  }, []);

  useEffect(() => {
    const current = view.current;
    if (current === null) return;
    if (current.state.doc.toString() !== text) {
      current.dispatch({ changes: { from: 0, to: current.state.doc.length, insert: text } });
    }
  }, [text]);

  return <div className="myx-toml" ref={host} />;
}

/**
 * The daemon's copy against the draft: what a write would change. Collapsed where the two agree,
 * so the diff is the change and not the file.
 */
export function TomlMerge({ original, modified }: { original: string; modified: string }) {
  const host = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const parent = host.current;
    if (parent === null) return;
    const created = new MergeView({
      parent,
      a: { doc: original, extensions: [tomlLanguage()] },
      b: { doc: modified, extensions: [tomlLanguage()] },
      collapseUnchanged: { margin: 2, minSize: 3 },
    });
    return () => created.destroy();
  }, [original, modified]);

  return <div className="myx-toml" ref={host} />;
}
