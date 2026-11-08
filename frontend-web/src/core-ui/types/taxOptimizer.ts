/**
 * taxOptimizer.ts — Tipos del Módulo 2 (Optimizador Fiscal)
 *
 * Espejo del contrato REST del backend Java:
 *   GET /api/v1/tax/{portfolioId}/estimate?isin=&qty=&price=
 *   GET /api/v1/tax/{portfolioId}/analyze/{isin}?qty=&price=
 *
 * Implementado en: com.wealth.mod2_taxoptimizer.TaxEvaluatorAdapter
 *
 * @module core-ui/types/taxOptimizer
 */

// ─── Respuesta de /estimate (FrictionCost del puerto SPI) ─────────────────────

/** Coste de fricción fiscal de una orden de venta. Espejo de TaxEvaluatorPort.FrictionCost */
export interface FrictionCost {
  /** Plusvalía (+) o minusvalía (-) estimada en EUR. 0 si orden BUY o sin historial. */
  taxableGainLoss: number;
  /** Impuesto estimado según tramos IRPF (19-28%). Siempre ≥ 0. */
  estimatedTaxCost: number;
  /** Número de lotes FIFO que se consumirían total o parcialmente. */
  affectedLots: number;
  /** true si la minusvalía quedaría diferida por la regla de los 2 meses (art. 33.5 LIRPF). */
  twoMonthRuleViolation: boolean;
  /** Mensaje descriptivo de warning. null si no hay violación ni recomendación. */
  warning: string | null;
}

// ─── Respuesta de /analyze/{isin} (análisis FIFO completo) ───────────────────

/** Snapshot del lote FIFO afectado por la venta. */
export interface TaxLotSnapshot {
  id: number;
  /** ISO-8601 date */
  purchaseDate: string;
  purchasePrice: number;
  availableQuantityBefore: number;
}

/** Impacto de la venta sobre un lote concreto. */
export interface LotImpact {
  lot: TaxLotSnapshot;
  quantityUsed: number;
  gainLoss: number;
}

/** Resultado FIFO completo de una venta teórica. */
export interface FifoResult {
  portfolioId: string;
  assetIsin: string;
  /** ISO-8601 date */
  saleDate: string;
  quantitySold: number;
  salePrice: number;
  affectedLots: LotImpact[];
  totalGainLoss: number;
  estimatedTax: number;
  quantityNotCovered: number;
}

/** Clasificación cualitativa de OpenJEV. */
export interface TaxClassification {
  hasSignificantTaxImpact: boolean;
  /** "none" | "low" | "medium" | "high" */
  twoMonthRiskLevel: string;
  recommendHarvesting: boolean;
  /** "short_term" | "medium_term" | "long_term" */
  lotAgeProfile: string;
  fromFallback: boolean;
}

/** Resultado de la validación local de la regla de los 2 meses. */
export interface TwoMonthValidation {
  violated: boolean;
  conflictingDates: string[];
  warning: string;
}

/** Respuesta completa del endpoint GET /analyze/{isin}. */
export interface TaxAnalysisResult {
  fifo: FifoResult;
  classification: TaxClassification;
  twoMonth: TwoMonthValidation;
}

// ─── Resumen fiscal por posición (calculado en el frontend a partir del plan) ─

/**
 * Datos fiscales enriquecidos para una posición concreta.
 * El frontend los calcula llamando al endpoint del Módulo 2 para las
 * posiciones que tienen orden de venta en el plan de rebalanceo.
 */
export interface PositionTaxSummary {
  instrumentId: string;
  frictionCost: FrictionCost | null;
  /** true si el fetch al backend Módulo 2 falló (modo degradado). */
  error: boolean;
}
