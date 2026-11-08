/**
 * Tipos del contrato REST del Módulo 1 — respuesta de
 *   GET /api/v1/portfolios/{portfolioId}/rebalance
 *
 * Espejo EXACTO del record Java `com.wealth.mod1_rebalancer.RebalancePlan` (schemaVersion 1.1)
 * (referencia: /contracts/mocks-contracts/rebalancer_base_mod1.json).
 * Si el backend cambia este contrato, hay que actualizar ambos lados (requiere consenso de equipo).
 *
 * Los importes llegan como números JSON (BigDecimal en notación plana):
 *   importes 2 decimales · participaciones 4 decimales (efectivo 2) · pesos en tanto por uno con 6 decimales ·
 *   porcentajes de P&L en porcentaje absoluto con 2 decimales (10.64 = +10,64 %).
 *
 * @module core-ui/types/rebalancePlan
 */

/** Enum `AssetClass` del backend (Java). Nota: difiere del `AssetClass` de portfolio.ts (COMMODITY vs ALTERNATIVE). */
export type BackendAssetClass = "EQUITY" | "FIXED_INCOME" | "COMMODITY" | "CASH";

export type OrderSide = "BUY" | "SELL";

export interface RebalanceOrder {
  instrumentId: string;
  name: string;
  side: OrderSide;
  /** Siempre > 0. Máximo 4 decimales. */
  quantity: number;
  price: number;
  /** quantity * price */
  amount: number;
  currency: string;
}

/** Posición de la cartera ACTUAL. */
export interface RebalancePositionRow {
  /** ISIN para instrumentos; "CASH:<moneda>" para el efectivo (que es un peso objetivo más). */
  instrumentId: string;
  /** null para el efectivo. */
  isin: string | null;
  name: string;
  assetClass: BackendAssetClass;
  quantity: number;
  price: number;
  marketValue: number;
  /** Coste de adquisición TOTAL de la posición. */
  costBasis: number;
  /** marketValue - costBasis */
  unrealizedPnLAmount: number;
  /** Porcentaje absoluto sobre el coste (0 si no hay coste). */
  unrealizedPnLPercent: number;
  currentWeight: number;
  targetWeight: number;
  /** objetivo - actual (positivo = comprar, negativo = vender) */
  deltaWeight: number;
  targetValue: number;
  deltaValue: number;
  /** null si no hay que operar esta posición. */
  order: RebalanceOrder | null;
  postTradeQuantity: number;
  postTradeValue: number;
  postTradeWeight: number;
}

/** Posición de la cartera PROYECTADA: cómo queda tras ejecutar las órdenes (coste base por coste medio). */
export interface ProjectedPosition {
  instrumentId: string;
  isin: string | null;
  name: string;
  assetClass: BackendAssetClass;
  quantity: number;
  price: number;
  marketValue: number;
  /** Peso tras ejecutar las órdenes. */
  weight: number;
  targetWeight: number;
  /** objetivo - proyectado (≈ 0 si se alcanza la meta). */
  deltaWeight: number;
  costBasis: number;
  unrealizedPnLAmount: number;
  unrealizedPnLPercent: number;
}

/** Rendimiento agregado de una cartera (el efectivo cuenta a valor nominal en el coste). */
export interface Performance {
  totalValue: number;
  totalCost: number;
  unrealizedPnLAmount: number;
  unrealizedPnLPercent: number;
}

export interface RebalanceConstraints {
  taxFrictionApplied: boolean;
  /** instrumentId → cantidad bloqueada (no vendible). Vacío en el Módulo 1 base. */
  lockedQuantities: Record<string, number>;
}

export interface CashFlowSummary {
  cashBefore: number;
  buyTotal: number;
  sellTotal: number;
  /** ventas - compras */
  netCashFlow: number;
  cashAfter: number;
  targetCash: number;
}

export interface RebalancePlan {
  schemaVersion: string;
  module: string;
  portfolioId: string;
  clientId: string;
  baseCurrency: string;
  /** ISO 8601 instant, ej. "2026-11-08T09:00:00Z" */
  asOf: string;
  totalValue: number;
  constraints: RebalanceConstraints;
  /** Cartera actual. */
  positions: RebalancePositionRow[];
  /** Ventas primero, luego compras. */
  orders: RebalanceOrder[];
  cashFlow: CashFlowSummary;
  /** Rendimiento de la cartera actual (tarjetas superiores). */
  performance: Performance;
  /** Cartera tras ejecutar las órdenes. */
  projectedPositions: ProjectedPosition[];
  projectedPerformance: Performance;
}
