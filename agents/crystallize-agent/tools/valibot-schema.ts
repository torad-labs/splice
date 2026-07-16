/**
 * Local valibotSchema — wraps a valibot schema as an AI SDK FlexibleSchema.
 *
 * @ai-sdk/valibot@2.0.20 has a peer dep on @ai-sdk/provider-utils@4.0.19 but
 * ai@6.0.97 ships 4.0.15. The unique schemaSymbol differs between versions so
 * the external package produces incompatible Schema types at the call sites.
 *
 * This shim uses jsonSchema() from ai (always the correct provider-utils version)
 * and valibot's safeParse for runtime validation — same semantics, no peer dep gap.
 */

import { jsonSchema } from "ai";
import { toJsonSchema } from "@valibot/to-json-schema";
import type { BaseSchema, BaseIssue } from "valibot";
import * as v from "valibot";

export function valibotSchema<T>(
  schema: BaseSchema<unknown, T, BaseIssue<unknown>>,
) {
  const jsonSchemaValue = toJsonSchema(schema) as Record<string, unknown>;
  return jsonSchema<T>(jsonSchemaValue, {
    validate: (value) => {
      const result = v.safeParse(schema, value);
      if (result.success) return { success: true as const, value: result.output as T };
      return {
        success: false as const,
        error: new Error(result.issues.map((i) => i.message).join("; ")),
      };
    },
  });
}
