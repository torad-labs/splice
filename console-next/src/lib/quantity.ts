/** Human units with exact integer base values: time stays milliseconds and size stays bytes. */
const TIME = [{ factor: 1, label: 'Milliseconds' }, { factor: 1000, label: 'Seconds' }] as const;
const SIZE = [{ factor: 1, label: 'Bytes' }, { factor: 1024, label: 'KiB' }, { factor: 1024 ** 2, label: 'MiB' }, { factor: 1024 ** 3, label: 'GiB' }] as const;
export const quantityUnits = (unit: 'ms' | 'bytes') => unit === 'ms' ? TIME : SIZE;

/** All supported factors have finite decimal expansions. Do not round the underlying integer to display it. */
export function quantityText(value: number, factor: number): string {
  const raw = BigInt(value);
  const magnitude = raw < 0n ? -raw : raw;
  const divisor = BigInt(factor);
  let remainder = magnitude % divisor;
  let fraction = '';
  while (remainder !== 0n) {
    remainder *= 10n;
    fraction += String(remainder / divisor);
    remainder %= divisor;
  }
  return `${raw < 0n ? '-' : ''}${magnitude / divisor}${fraction === '' ? '' : '.' + fraction}`;
}

/** Reject partial base units and unsafe integers, including decimals that floating-point multiplication would misround. */
export function quantityValue(text: string, factor: number): number | null {
  const match = /^([+-]?)(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?$/i.exec(text.trim());
  if (match === null || (match[2] === '' && (match[3] ?? '') === '')) return null;
  const digits = ((match[2] ?? '') + (match[3] ?? '')).replace(/^0+/, '');
  if (digits === '') return 0;
  const power = Number(match[4] ?? 0) - (match[3]?.length ?? 0);
  // Outside this range a nonzero value cannot be an integer within the safe base-unit range.
  if (power > 16 || power < -digits.length - String(factor).length) return null;
  const coefficient = BigInt(digits) * BigInt(factor) * (match[1] === '-' ? -1n : 1n);
  const denominator = power < 0 ? 10n ** BigInt(-power) : 1n;
  const numerator = power > 0 ? coefficient * 10n ** BigInt(power) : coefficient;
  if (numerator % denominator !== 0n) return null;
  const value = Number(numerator / denominator);
  return Number.isSafeInteger(value) ? value : null;
}
