/**
 * Tipos canónicos de la Plataforma Patrimonial — Módulo 1 (Balanceador Core)
 *
 * Diseñados para ser extensibles: los campos de Módulos 2, 3 y 4 ya están
 * presentes como opcionales (`?`) para no romper el contrato cuando se activen.
 *
 * @module core-ui/types/portfolio
 */

// ─── Enumeraciones ────────────────────────────────────────────────────────────

export type AssetClass =
  | "EQUITY"
  | "FIXED_INCOME"
  | "CASH"
  | "ALTERNATIVE"
  | "REAL_ESTATE";

export type AssetSubClass =
  | "ETF_GLOBAL"
  | "ETF_SECTOR"
  | "STOCK"
  | "GOVERNMENT_BONDS"
  | "CORPORATE_BONDS"
  | "CASH"
  | "HEDGE_FUND"
  | "PRIVATE_EQUITY";

// ─── Flags de posición (extensibles por Módulos 2, 3 y 4) ───────────────────

export interface PositionFlags {
  /** Módulo 4: Posición con lotes pignorados como garantía de préstamo lombardo */
  isPledged: boolean;
  /** Módulo 2: Lotes comprados hace menos de 2 meses (regla AEAT anti-lavado pérdidas) */
  taxWarning2Month: boolean;
  /** Módulo 2/3: Existen lotes fiscales abiertos con datos FIFO disponibles */
  hasOpenTaxLots: boolean;
}

// ─── Info de pignoración (Módulo 4) ─────────────────────────────────────────

export interface PledgeInfo {
  pledgeContractId: string;
  pledgedQuantity: number;
  loanId: string;
  /** Loan-To-Value actual. Rango 0–1. */
  ltvRatio: number;
  /** LTV umbral de warning (ej: 0.65) */
  ltvThresholdWarning: number;
  /** LTV umbral de liquidación forzosa (ej: 0.75) */
  ltvThresholdLiquidation: number;
}

// ─── Resumen de lotes fiscales (Módulos 2 y 3) ──────────────────────────────

export interface TaxLotsSummary {
  lotCount: number;
  /** ISO 8601 date string */
  oldestAcquisitionDate: string;
  /** ISO 8601 date string */
  newestAcquisitionDate: string;
}

// ─── P&L de posición ─────────────────────────────────────────────────────────

export interface PositionPnl {
  unrealized: number;
  /** Porcentaje absoluto (ej: 19.93 significa +19.93%) */
  unrealizedPct: number;
}

// ─── Posición individual de cartera ──────────────────────────────────────────

export interface AssetPosition {
  /** Identificador primario. Para Cash se usa 'CASH_EUR'. */
  isin: string;
  ticker: string;
  name: string;
  assetClass: AssetClass;
  subClass: AssetSubClass;
  currency: string;
  quantity: number;
  avgCostPrice: number;
  currentPrice: number;
  /** Valor de mercado en moneda base del portfolio */
  currentValue: number;
  /** Peso actual en portfolio (0–1) */
  currentWeight: number;
  /** Peso objetivo en portfolio (0–1) */
  targetWeight: number;
  pnl: PositionPnl;
  flags: PositionFlags;
  /** null si la posición no está pignorada */
  pledgeInfo: PledgeInfo | null;
  /** null para posiciones de tipo CASH */
  taxLotsSummary: TaxLotsSummary | null;
}

// ─── Meta del portfolio ──────────────────────────────────────────────────────

export interface PortfolioMeta {
  portfolioId: string;
  clientId: string;
  clientName: string;
  baseCurrency: string;
  /** ISO 8601 date string */
  valuationDate: string;
  totalMarketValue: number;
  totalCostBasis: number;
  totalUnrealizedPnl: number;
  /** Porcentaje absoluto (ej: 13.86 significa +13.86%) */
  totalUnrealizedPnlPct: number;
}

// ─── Respuesta completa del mock / futura API ────────────────────────────────

export interface CustodyPositionsResponse {
  meta: PortfolioMeta;
  positions: AssetPosition[];
}
