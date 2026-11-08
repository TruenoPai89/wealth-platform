/**
 * format.ts — Formateadores es-ES compartidos por el Módulo 1.
 *
 * - Importes: siempre 2 decimales y separador de miles también en 4 cifras (9.500,00 €).
 *   (Intl en es-ES omite el separador de miles por debajo de 10.000; useGrouping "always" lo fuerza.)
 * - Participaciones: máximo 4 decimales, sin ceros sobrantes (52,3810 → "52,381"; 400 → "400").
 * - Pesos: porcentaje con 2 decimales (0,2923 → "29,23 %").
 *
 * @module core-ui/format
 */

const GROUPING = { useGrouping: "always" } as const;

const EUR = new Intl.NumberFormat("es-ES", {
  style: "currency",
  currency: "EUR",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  ...GROUPING,
});

const UNITS = new Intl.NumberFormat("es-ES", {
  minimumFractionDigits: 0,
  maximumFractionDigits: 4,
  ...GROUPING,
});

const WEIGHT = new Intl.NumberFormat("es-ES", {
  style: "percent",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** 7500 → "7.500,00 €" */
export function formatEur(value: number): string {
  return EUR.format(value);
}

/** 52.381 → "52,381" (máx. 4 decimales, sin ceros finales) */
export function formatUnits(value: number): string {
  return UNITS.format(value);
}

/** Peso en tanto por uno: 0.2923 → "29,23 %" */
export function formatWeight(value: number): string {
  return WEIGHT.format(value);
}
