/**
 * Custom Output Validation
 *
 * Validates that customOutputCode fields in the scaffold implement
 * the Output interface correctly. This is a phantom dependency —
 * without it, the scaffold produces customOutputCode that looks
 * right but doesn't parse.
 *
 * The Output interface requires:
 *   { type: "custom", responseFormat: { type: "text" | "json" }, parseOutput: async ({ text }) => ... }
 *
 * This module checks:
 * 1. Structure — has type, responseFormat, parseOutput fields
 * 2. parseOutput returns { success, value } or { success, error }
 * 3. No obvious syntax errors in the code string
 */

import type { ScaffoldOutput } from "../schemas.js";

export interface ValidationResult {
  valid: boolean;
  toolName: string;
  errors: string[];
  warnings: string[];
}

/**
 * Validate all customOutputCode fields in a scaffold.
 * Returns validation results per tool. Tools without custom Output are skipped.
 */
export function validateCustomOutputs(scaffold: ScaffoldOutput): ValidationResult[] {
  const results: ValidationResult[] = [];

  for (const tool of scaffold.tools) {
    if (tool.outputStrategy !== "custom") continue;

    const result: ValidationResult = {
      valid: true,
      toolName: tool.name,
      errors: [],
      warnings: [],
    };

    // Check: customOutputCode must exist when strategy is "custom"
    if (!tool.customOutputCode) {
      result.valid = false;
      result.errors.push("outputStrategy is 'custom' but customOutputCode is null/empty");
      results.push(result);
      continue;
    }

    // Check: customOutputReason should explain why
    if (!tool.customOutputReason) {
      result.warnings.push(
        "customOutputReason is empty — document why built-in Output is insufficient"
      );
    }

    const code = tool.customOutputCode;

    // Structural checks on the code string
    validateOutputStructure(code, result);
    validateParseOutputReturn(code, result);
    validateSyntaxBasics(code, result);

    results.push(result);
  }

  return results;
}

function validateOutputStructure(code: string, result: ValidationResult): void {
  // Must contain type: "custom"
  if (!code.includes("type:") || (!code.includes('"custom"') && !code.includes("'custom'"))) {
    result.valid = false;
    result.errors.push('Missing type: "custom" field');
  }

  // Must contain responseFormat
  if (!code.includes("responseFormat")) {
    result.valid = false;
    result.errors.push("Missing responseFormat field");
  }

  // Must contain parseOutput
  if (!code.includes("parseOutput")) {
    result.valid = false;
    result.errors.push("Missing parseOutput field");
  }

  // responseFormat must specify type
  const rfMatch = code.match(/responseFormat\s*:\s*\{[^}]*type\s*:\s*["'](\w+)["']/);
  if (rfMatch) {
    const formatType = rfMatch[1];
    if (formatType !== "text" && formatType !== "json") {
      result.valid = false;
      result.errors.push(`responseFormat.type must be "text" or "json", got "${formatType}"`);
    }
  } else if (code.includes("responseFormat")) {
    result.warnings.push("Could not parse responseFormat.type — verify it's 'text' or 'json'");
  }
}

function validateParseOutputReturn(code: string, result: ValidationResult): void {
  // parseOutput must return { success: true, value } or { success: false, error }
  const hasSuccessTrue = code.includes("success: true") || code.includes("success:true");
  const hasSuccessFalse = code.includes("success: false") || code.includes("success:false");

  if (!hasSuccessTrue) {
    result.valid = false;
    result.errors.push("parseOutput never returns { success: true, value: ... }");
  }

  if (!hasSuccessFalse) {
    result.warnings.push(
      "parseOutput never returns { success: false, error: ... } — " +
        "no error handling for malformed model output"
    );
  }

  // Check for value field in success path
  if (hasSuccessTrue && !code.includes("value:") && !code.includes("value :")) {
    result.valid = false;
    result.errors.push("parseOutput success path missing 'value' field");
  }
}

function validateSyntaxBasics(code: string, result: ValidationResult): void {
  // Check balanced braces
  let depth = 0;
  for (const char of code) {
    if (char === "{") depth++;
    if (char === "}") depth--;
    if (depth < 0) {
      result.valid = false;
      result.errors.push("Unbalanced braces — extra closing brace");
      return;
    }
  }
  if (depth !== 0) {
    result.valid = false;
    result.errors.push(`Unbalanced braces — ${depth} unclosed`);
  }

  // Check balanced parens
  let parenDepth = 0;
  for (const char of code) {
    if (char === "(") parenDepth++;
    if (char === ")") parenDepth--;
    if (parenDepth < 0) {
      result.valid = false;
      result.errors.push("Unbalanced parentheses");
      return;
    }
  }
  if (parenDepth !== 0) {
    result.valid = false;
    result.errors.push(`Unbalanced parentheses — ${parenDepth} unclosed`);
  }

  // Check for async parseOutput
  if (code.includes("parseOutput") && !code.includes("async")) {
    result.warnings.push("parseOutput should be async — the interface expects Promise return");
  }

  // Check for text destructuring
  if (code.includes("parseOutput") && !code.includes("{ text }") && !code.includes("{text}")) {
    result.warnings.push(
      "parseOutput parameter should destructure { text } from the model response"
    );
  }
}

/**
 * Quick check: does the scaffold have any tools that SHOULD use custom Output
 * but don't? Based on outputFields containing code/schema/template types.
 */
export function detectMissingCustomOutputs(
  scaffold: ScaffoldOutput
): Array<{ toolName: string; reason: string }> {
  const missing: Array<{ toolName: string; reason: string }> = [];

  for (const tool of scaffold.tools) {
    if (tool.outputStrategy === "custom") continue;

    // Check if outputFields suggest domain syntax
    const codeSignals = tool.outputFields.filter(
      (f) =>
        f.type.toLowerCase().includes("code") ||
        f.type.toLowerCase().includes("schema") ||
        f.type.toLowerCase().includes("template") ||
        f.type.toLowerCase().includes("sql") ||
        f.type.toLowerCase().includes("regex") ||
        f.type.toLowerCase().includes("expression")
    );

    if (codeSignals.length > 0) {
      missing.push({
        toolName: tool.name,
        reason: `outputFields contain domain syntax types: ${codeSignals.map((f) => f.name).join(", ")}. Consider custom Output with domain-specific parser.`,
      });
    }
  }

  return missing;
}
