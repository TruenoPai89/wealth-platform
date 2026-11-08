/**
 * Tipos de las agrupaciones de fondos (cestas) y de la aportación de efectivo — espejo de los records Java
 * `Basket`, `ContributionPlan` y `ContributionService.ContributionExecution` (com.wealth.mod1_rebalancer).
 * Endpoints:
 *   GET  /api/v1/portfolios/{id}/baskets
 *   POST /api/v1/portfolios/{id}/baskets/{basketId}/contribution/preview   body { amount }  → ContributionPlan
 *   POST /api/v1/portfolios/{id}/baskets/{basketId}/contribution           body { amount }  → ContributionExecution
 *
 * Importes con 2 decimales, participaciones con 4, pesos en tanto por uno con 6.
 *
 * @module core-ui/types/baskets
 */

import type { Performance, ProjectedPosition } from "@/core-ui/types/rebalancePlan";
import type { RebalanceOrder } from "@/core-ui/types/rebalancePlan";
import type { Transfer } from "@/core-ui/types/transfers";

export interface Basket {
  id: string;
  name: string;
  description: string;
  /** instrumentId → peso objetivo dentro de la cesta (suma 1). Sin efectivo. */
  weights: Record<string, number>;
}

/** Un fondo de la agrupación dentro de la propuesta. */
export interface ContributionFundRow {
  instrumentId: string;
  name: string;
  /** Peso objetivo en la cesta. */
  basketWeight: number;
  currentValue: number;
  /** Peso real dentro de la cesta, antes de comprar. */
  currentWeightInBasket: number;
  buyAmount: number;
  buyQuantity: number;
  valueAfter: number;
  /** Peso real dentro de la cesta, tras comprar. */
  weightInBasketAfter: number;
}

export interface ContributionPlan {
  portfolioId: string;
  basketId: string;
  basketName: string;
  baseCurrency: string;
  asOf: string;
  amount: number;
  invested: number;
  /** Aportación - invertido (céntimos por redondear participaciones): se queda en efectivo. */
  unallocated: number;
  funds: ContributionFundRow[];
  /** Solo compras: una aportación nunca vende. */
  orders: RebalanceOrder[];
  cashBefore: number;
  cashAfter: number;
  /** Cartera completa tras invertir, frente a la meta de la cartera. */
  projectedPositions: ProjectedPosition[];
  performance: Performance;
  projectedPerformance: Performance;
}

export interface ContributionExecution {
  executionId: string;
  portfolioId: string;
  basketId: string;
  basketName: string;
  executedAt: string;
  journalReference: string;
  transfers: Transfer[];
  plan: ContributionPlan;
}
