import { parseLosslessJson } from "./current-witness";

/** Schema budgets are Rust usize/u64 values, including values above signed Long. */
export function budgetBoundaryFixtures(): Record<string, any>[] {
  const schema = { type: "object", properties: { value: { type: "string", description: "X".repeat(20_000) } } };
  const rows: Record<string, any>[] = [];
  for (const literal of ["9223372036854775808", "18446744073709551615", "2147483648", "0", "1", "16000", "24000", '"24000"', "true", "24000.0", "-1", "18446744073709551616"]) {
    const budget = parseLosslessJson(literal), tool = { name: "large", description: "schema budget discriminator", input_schema: schema };
    for (const operation of ["describe", "augment", "prepare"]) rows.push({ operation, tools: [{ ...tool, input_schema_max_bytes: budget }] });
    for (const operation of ["augment", "prepare"]) rows.push({ operation, tools: [tool], code_mode_input_schema_max_bytes: budget });
    rows.push({ operation: "collect", specs: [{ type: "function", name: tool.name, description: tool.description, parameters: schema }], code_mode_input_schema_max_bytes: budget });
  }
  return rows;
}
