// Layers, low to high: types (the daemon's payload shapes), lib, api, ui, pages, app. An import may only go down. The network is one
// file (src/api/client.ts): fetch is a lint error anywhere else, so a page can only ask for data
// through the api layer's typed hooks.
import js from '@eslint/js';
import tseslint from 'typescript-eslint';

const down = (banned) => ({
  patterns: banned.map((layer) => ({
    group: [`**/${layer}`, `**/${layer}/**`, `@${layer}/**`],
    message: `${layer} sits above this layer: an import may only go down (lib, api, ui, pages, app).`,
  })),
});

export default tseslint.config(
  js.configs.recommended,
  ...tseslint.configs.strict,
  {
    files: ['src/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-globals': ['error', { name: 'fetch', message: 'The network is src/api/client.ts only.' }],
    },
  },
  { files: ['src/api/client.ts'], rules: { 'no-restricted-globals': 'off' } },
  { files: ['src/types/**'], rules: { 'no-restricted-imports': ['error', down(['lib', 'api', 'ui', 'pages', 'app'])] } },
  { files: ['src/lib/**'], rules: { 'no-restricted-imports': ['error', down(['api', 'ui', 'pages', 'app'])] } },
  { files: ['src/api/**'], rules: { 'no-restricted-imports': ['error', down(['ui', 'pages', 'app'])] } },
  { files: ['src/ui/**'], rules: { 'no-restricted-imports': ['error', down(['api', 'pages', 'app'])] } },
  { files: ['src/pages/**'], rules: { 'no-restricted-imports': ['error', down(['app'])] } },
);
