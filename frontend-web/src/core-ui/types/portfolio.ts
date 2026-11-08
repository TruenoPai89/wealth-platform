/**
 * Tipos canónicos de la Plataforma Patrimonial — Módulo 1 (Balanceador Core)
 *
 * Alineados con el contrato de Raúl:
 *   /contracts/mocks-contracts/rebalancer_base_mod1.json
 *   RebalancePlan.java (backend-core-java)
 *
 * Los campos exclusivos del frontend (ticker, subClass, avgCostPrice, pnl,
 * flags, pledgeInfo, taxLotsSummary) se mantienen como opcionales (?).
 * El backend base NO los devuelve; los Módulos 2, 3 y 4 los enriquecerán.
 *
 * @module core-ui/types/portfolio
 */

// ─── Enumeraciones (alineadas con AssetClass.java) ───────────────────────────

export type AssetClass =
  | "EQUITY"
  | "FIXED_INCOME"
  | "COMMODITY"   // añadido por Raúl (Invesco Gold, etc.)
  | "CASH"
  | "ALTERNATIVE" // reservado para fases futuras (no está en Java aún)
  | "REAL_ESTATE"; // reservado para fases futuras (no está en Java aún)

export type AssetSubClass =
  | "ETF_GLOBAL"
  | "ETF_SECTOR"
  | "STOCK"
  | "GOVERNMENT_BONDS"
  | "CORPORATE_BONDS"
  | "CASH"
  | "HEDGE_FUND"
  | "PRIVATE_EQUITY";

// ─── Orden embebida por posición (RebalanceOrder.java) ───────────────────────

export type OrderSide = "BUY" | "SELL";

export interface EmbeddedOrder {
  instrumentId: string;
  name: string;
  side: OrderSide;
  quantity: number;
  price: number;
  amount: number;
  currency: string;
}

// ─── Constraints del rebalanceo (Módulos 2 y 4 las rellenan) ────────────────

export interface RebalanceConstraints {
  /** Módulo 2: true cuando se ha aplicado fricción fiscal al cálculo */
  taxFrictionApplied: boolean;
  /** Módulo 4: mapa instrumentId → cantidad bloqueada (pignorada) */
  lockedQuantities: Record<string, number>;
}

// ─── Flags de posición — exclusivos del frontend (Módulos 2, 3 y 4) ─────────

export interface PositionFlags {
  /** Módulo 4: Posición con lotes pignorados como garantía de préstamo lombardo */
  isPledged: boolean;
  /** Módulo 2: Lotes comprados hace menos de 2 meses (regla AEAT anti-lavado pérdidas) */
  taxWarning2Month: boolean;
  /** Módulo 2/3: Existen lotes fiscales abiertos con datos FIFO disponibles */
  hasOpenTaxLots: boolean;
}

// ─── Info de pignoración — exclusivo del frontend (Módulo 4) ─────────────────

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

// ─── Resumen de lotes fiscales — exclusivo del frontend (Módulos 2 y 3) ──────

export interface TaxLotsSummary {
  lotCount: number;
  /** ISO 8601 date string */
  oldestAcquisitionDate: string;
  /** ISO 8601 date string */
  newestAcquisitionDate: string;
}

// ─── P&L — exclusivo del frontend (Módulo 3) ─────────────────────────────────

export interface PositionPnl {
  unrealized: number;
  /** Porcentaje absoluto (ej: 19.93 significa +19.93%) */
  unrealizedPct: number;
}

// ─── Posición individual (PositionRow de RebalancePlan.java) ─────────────────

export interface AssetPosition {
  // ── Campos del contrato de Raúl (siempre presentes) ──────────────────────
  /** ID primario del instrumento. Cash usa formato "CASH:EUR" */
  instrumentId: string;
  /** ISIN del instrumento. null para posiciones de Cash */
  isin: string | null;
  name: string;
  assetClass: AssetClass;
  quantity: number;
  /** Precio de mercado actual en moneda del instrumento */
  price: number;
  /** Valor de mercado en moneda base del portfolio */
  marketValue: number;
  /** Peso actual en portfolio (0–1) */
  currentWeight: number;
  /** Peso objetivo en portfolio (0–1) */
  targetWeight: number;
  /** objetivo - actual. Positivo = comprar, negativo = vender */
  deltaWeight: number;
  /** Valor objetivo en moneda base */
  targetValue: number;
  /** Diferencia de valor. Positivo = comprar, negativo = vender */
  deltaValue: number;
  /** Orden propuesta para esta posición. null si no hay movimiento necesario */
  order: EmbeddedOrder | null;
  postTradeQuantity: number;
  postTradeValue: number;
  postTradeWeight: number;

  // ── Campos exclusivos del frontend (opcionales hasta que los módulos los rellenen) ──
  /** Ticker corto. No viene del backend base. */
  ticker?: string;
  /** Subclase de activo. No viene del backend base. */
  subClass?: AssetSubClass;
  /** Moneda del instrumento. No viene del backend base (se infiere de instrumentId para Cash). */
  currency?: string;
  /** Precio medio de coste. Módulo 3 (IRPF) lo necesita. */
  avgCostPrice?: number;
  /** P&L no realizado. Módulo 3 lo calcula. */
  pnl?: PositionPnl;
  /** Flags de estado fiscal y pignoración. Módulos 2 y 4. */
  flags?: PositionFlags;
  /** Info de pignoración. Módulo 4. */
  pledgeInfo?: PledgeInfo | null;
  /** Resumen de lotes fiscales. Módulos 2 y 3. */
  taxLotsSummary?: TaxLotsSummary | null;
}

// ─── Resumen de flujo de caja (CashFlowSummary de RebalancePlan.java) ────────

export interface CashFlowSummary {
  cashBefore: number;
  buyTotal: number;
  sellTotal: number;
  /** Positivo = el rebalanceo libera caja; negativo = consume caja */
  netCashFlow: number;
  cashAfter: number;
  targetCash: number;
}

// ─── Respuesta completa del backend (RebalancePlan.java) ─────────────────────

export interface RebalancePlan {
  schemaVersion: string;
  module: string;
  portfolioId: string;
  clientId: string;
  baseCurrency: string;
  /** ISO 8601 datetime string (Instant de Java) */
  asOf: string;
  totalValue: number;
  constraints: RebalanceConstraints;
  positions: AssetPosition[];
  /** Lista de órdenes consolidadas (SELL primero, luego BUY) */
  orders: EmbeddedOrder[];
  cashFlow: CashFlowSummary;
}

// ─── Alias de compatibilidad hacia atrás (usado en mockPortfolioService) ─────
// Cuando el backend devuelva RebalancePlan, eliminar este alias y CustodyPositionsResponse.

/** @deprecated Usar RebalancePlan. Este tipo representa el mock local anterior. */
export interface PortfolioMeta {
  portfolioId: string;
  clientId: string;
  clientName: string;
  baseCurrency: string;
  valuationDate: string;
  totalMarketValue: number;
  totalCostBasis: number;
  totalUnrealizedPnl: number;
  totalUnrealizedPnlPct: number;
}

/** @deprecated Usar RebalancePlan. */
export interface CustodyPositionsResponse {
  meta: PortfolioMeta;
  positions: AssetPosition[];
}
