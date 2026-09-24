// The architecture is toolchain-enforced, not documented (locked decision):
// FSD layers are strictly unidirectional, slices export through their index
// barrel, and the mgmt HTTP client is importable ONLY from entity api segments
// (the UI-via-state gate). A file outside the architecture cannot exist.
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import boundaries from 'eslint-plugin-boundaries';

// A slice's public door. Every cross-slice import must land on one (the v6
// entry-point rule, folded into dependencies by eslint-plugin-boundaries v7).
const DOOR = ['index.ts', 'index.tsx'];
const through = (...type) => ({ to: { element: { type, fileInternalPath: DOOR } } });
const sameSlice = (type) => ({
  to: { element: { type, captured: { slice: '{{ from.element.captured.slice }}' } } },
});

export default tseslint.config(
  js.configs.recommended,
  ...tseslint.configs.strict,
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { boundaries },
    settings: {
      'boundaries/root-path': import.meta.dirname,
      // Aliases MUST resolve to local files or boundaries would treat them as
      // external packages and silently skip validation.
      'import/resolver': {
        typescript: { alwaysTryTypes: true, project: './tsconfig.json' },
      },
      // The stylesheets at src/shared/ (tokens.css, fonts.css) are ignored, so a
      // script file there matches no element and no-unknown-files refuses it.
      'boundaries/ignore': ['**/*.css'],
      'boundaries/elements': [
        // order matters: most specific first
        { type: 'shared-api', pattern: 'src/shared/api' },
        { type: 'shared', pattern: 'src/shared/*' },
        { type: 'entities-api', pattern: 'src/entities/*/api', capture: ['slice'] },
        { type: 'entities', pattern: 'src/entities/*', capture: ['slice'] },
        { type: 'features', pattern: 'src/features/*' },
        { type: 'widgets', pattern: 'src/widgets/*' },
        { type: 'pages', pattern: 'src/pages/*' },
        { type: 'app', pattern: 'src/app' },
      ],
    },
    rules: {
      // A file outside the architecture cannot exist.
      'boundaries/no-unknown-files': 'error',
      'boundaries/no-unknown-dependencies': 'error',
      // Strictly unidirectional layers. Same-layer cross-imports are denied by
      // omission (feature never imports feature, entity never imports entity —
      // the slice capture permits only a slice's OWN api segment).
      // shared-api appears ONLY in entities-api's allow list: the HTTP client
      // is unreachable from any view code (UI strictly via state).
      // Slices own a public API: outside entities, every allow lands on a DOOR,
      // so a deep import is a lint error. Entity-internal segment wiring
      // (index ↔ model ↔ api within ONE slice) may address segment files by
      // path; the layer and slice limits still hold there.
      'boundaries/dependencies': ['error', {
        default: 'disallow',
        policies: [
          { from: { element: { type: 'app' } }, allow: through('pages', 'widgets', 'features', 'entities', 'shared') },
          { from: { element: { type: 'pages' } }, allow: through('widgets', 'features', 'entities', 'shared') },
          { from: { element: { type: 'widgets' } }, allow: through('features', 'entities', 'shared') },
          { from: { element: { type: 'features' } }, allow: through('entities', 'shared') },
          { from: { element: { type: 'entities' } }, allow: [{ to: { element: { type: 'shared' } } }, sameSlice('entities-api')] },
          {
            from: { element: { type: 'entities-api' } },
            allow: [{ to: { element: { type: ['shared-api', 'shared'] } } }, sameSlice('entities')],
          },
          { from: { element: { type: 'shared' } }, allow: through('shared') },
          { from: { element: { type: 'shared-api' } }, allow: through('shared') },
          // Payload TYPES flow freely; the client VALUE stays locked to
          // entities-api (type-only imports carry no HTTP capability).
          {
            from: { element: { type: ['features', 'widgets', 'pages', 'app'] } },
            allow: { ...through('shared-api'), dependency: { kind: 'type' } },
          },
          { from: { element: { type: 'entities' } }, allow: { to: { element: { type: 'shared-api' } }, dependency: { kind: 'type' } } },
        ],
      }],
    },
  },
  {
    files: ['tests/**/*.ts'],
    rules: {},
  },
);
