import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default [
  js.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  ...tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        projectService: {
          allowDefaultProject: ["eslint.config.js"],
        },
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // Enforce explicit return types on public API functions
      "@typescript-eslint/explicit-function-return-type": "off",
      "@typescript-eslint/explicit-module-boundary-types": "off",

      // Allow non-null assertions when the type system can't help
      "@typescript-eslint/no-non-null-assertion": "warn",

      // These casts are necessary for SDK interop
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/no-unsafe-assignment": "warn",
      "@typescript-eslint/no-unsafe-member-access": "warn",
      "@typescript-eslint/no-unsafe-call": "warn",
      "@typescript-eslint/no-unsafe-return": "warn",
      "@typescript-eslint/no-unsafe-argument": "warn",

      // Numbers and booleans are valid in template literals
      "@typescript-eslint/restrict-template-expressions": [
        "error",
        { allowNumber: true, allowBoolean: true },
      ],

      // Empty arrow functions are used intentionally for noop span fns
      "@typescript-eslint/no-empty-function": [
        "error",
        { allow: ["arrowFunctions"] },
      ],

      // Enforce consistent type imports
      "@typescript-eslint/consistent-type-imports": [
        "error",
        { prefer: "type-imports", fixStyle: "separate-type-imports" },
      ],

      // No floating promises — all async calls must be awaited or handled
      "@typescript-eslint/no-floating-promises": "error",

      // No misused promises (e.g. passing async fn where sync is expected)
      "@typescript-eslint/no-misused-promises": "error",

      // Exhaustive switch — catch unhandled union members
      "@typescript-eslint/switch-exhaustiveness-check": "error",

      // Prefer nullish coalescing over ||
      "@typescript-eslint/prefer-nullish-coalescing": "error",

      // Require await in async functions
      "@typescript-eslint/require-await": "error",

      // No unnecessary type assertions
      "@typescript-eslint/no-unnecessary-type-assertion": "error",

      // No unused vars (with underscore escape hatch)
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
    },
  },
  {
    ignores: ["node_modules/**", "dist/**"],
  },
];
