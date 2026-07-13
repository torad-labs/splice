// The architecture is toolchain-enforced, not documented (locked decision):
// FSD layers are strictly unidirectional, slices export through their index
// barrel, and the mgmt HTTP client is importable ONLY from entity api segments
// (the UI-via-state gate). A file outside the architecture cannot exist.
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import boundaries from 'eslint-plugin-boundaries';

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
      'boundaries/ignore': ['**/*.css'],
      'boundaries/elements': [
        // order matters: most specific first
        { type: 'shared-api', pattern: 'src/shared/api', mode: 'folder' },
        { type: 'shared', pattern: 'src/shared/*', mode: 'folder' },
        { type: 'shared-root', pattern: 'src/shared/*.*', mode: 'file' },
        { type: 'entities-api', pattern: 'src/entities/*/api', mode: 'folder', capture: ['slice'] },
        { type: 'entities', pattern: 'src/entities/*', mode: 'folder', capture: ['slice'] },
        { type: 'features', pattern: 'src/features/*', mode: 'folder' },
        { type: 'widgets', pattern: 'src/widgets/*', mode: 'folder' },
        { type: 'pages', pattern: 'src/pages/*', mode: 'folder' },
        { type: 'app', pattern: 'src/app', mode: 'folder' },
      ],
    },
    rules: {
      // A file outside the architecture cannot exist.
      'boundaries/no-unknown-files': 'error',
      'boundaries/no-unknown': 'error',
      // Strictly unidirectional layers. Same-layer cross-imports are denied by
      // omission (feature never imports feature, entity never imports entity —
      // the ${slice} matcher permits only a slice's OWN api segment).
      // shared-api appears ONLY in entities-api's allow list: the HTTP client
      // is unreachable from any view code (UI strictly via state).
      'boundaries/element-types': ['error', {
        default: 'disallow',
        rules: [
          { from: 'app', allow: ['pages', 'features', 'entities', 'shared', 'shared-root'] },
          { from: 'pages', allow: ['widgets', 'features', 'entities', 'shared'] },
          { from: 'widgets', allow: ['features', 'entities', 'shared'] },
          { from: 'features', allow: ['entities', 'shared'] },
          { from: 'entities', allow: ['shared', ['entities-api', { slice: '${from.slice}' }]] },
          { from: 'entities-api', allow: ['shared-api', 'shared', ['entities', { slice: '${from.slice}' }]] },
          { from: 'shared', allow: ['shared'] },
          { from: 'shared-api', allow: ['shared'] },
          // Payload TYPES flow freely; the client VALUE stays locked to
          // entities-api (type-only imports carry no HTTP capability).
          { from: ['entities', 'features', 'widgets', 'pages', 'app'], allow: ['shared-api'], importKind: 'type' },
        ],
      }],
      // Slices own a public API: deep imports are lint errors.
      'boundaries/entry-point': ['error', {
        default: 'disallow',
        rules: [
          { target: ['shared', 'shared-api'], allow: ['index.ts', 'index.tsx'] },
          { target: ['shared-root'], allow: ['*.css'] },
          { target: ['entities', 'features', 'widgets', 'pages', 'app'], allow: ['index.ts', 'index.tsx'] },
          { target: ['entities-api'], allow: ['index.ts'] },
        ],
      }],
    },
  },
  {
    // entity-internal segment wiring (index ↔ model ↔ api within ONE slice)
    // may address segment files by relative path; boundaries still gates every
    // cross-slice and cross-layer import above.
    files: ['src/entities/**/*.ts'],
    rules: {
      'boundaries/entry-point': 'off',
    },
  },
  {
    files: ['tests/**/*.ts'],
    rules: {},
  },
);
