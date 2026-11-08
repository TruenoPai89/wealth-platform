/**
 * rebalancePlanAdapter.ts
 *
 * Prepara el plan del backend (RebalancePlan v1.1, ver types/rebalancePlan.ts) para
 * PositionsTableWithSlots.tsx, que consume el `RebalancePlan` de types/portfolio.ts.
 *
 * Dos vistas del mismo plan:
 *   - "current":   cartera actual (positions + performance), tal cual llega del backend.
 *   - "projected": cartera tras ejecutar las órdenes (projectedPositions + projectedPerformance). El "peso actual"
 *                  de la tabla pasa a ser el peso proyectado, de modo que se vea cómo se ajustan a la meta.
 *
 * Coste, P&L y totales vienen YA CALCULADOS por el backend: aquí no se calcula nada financiero.
 * Si el plan no trae proyección (p. ej. el mock antiguo, schemaVersion 1.0), "projected" cae a la cartera actual.
 *
 * @module core-ui/services/rebalancePlanAdapter
 */

import type {
  AssetPosition,
  RebalancePlan as DisplayPlan,
} from "@/core-ui/types/portfolio";
import type {
  ProjectedPosition,
  RebalancePlan,
} from "@/core-ui/types/rebalancePlan";

export type PortfolioViewMode = "current" | "projected";

/** Una posición proyectada como fila de la tabla: ya operada, sin orden pendiente ni delta. */
function projectedToPosition(p: ProjectedPosition): AssetPosition {
  return {
    instrumentId: p.instrumentId,
    isin: p.isin,
    name: p.name,
    assetClass: p.assetClass,
    quantity: p.quantity,
    price: p.price,
    marketValue: p.marketValue,
    currentWeight: p.weight,
    targetWeight: p.targetWeight,
    deltaWeight: p.deltaWeight,
    targetValue: p.marketValue,
    deltaValue: 0,
    order: null,
    postTradeQuantity: p.quantity,
    postTradeValue: p.marketValue,
    postTradeWeight: p.weight,
    costBasis: p.costBasis,
    unrealizedPnLAmount: p.unrealizedPnLAmount,
    unrealizedPnLPercent: p.unrealizedPnLPercent,
  };
}

export function toDisplayPlan(
  plan: RebalancePlan,
  mode: PortfolioViewMode = "current",
): DisplayPlan {
  if (mode === "projected" && plan.projectedPositions && plan.projectedPerformance) {
    return {
      ...plan,
      totalValue: plan.projectedPerformance.totalValue,
      performance: plan.projectedPerformance,
      positions: plan.projectedPositions.map(projectedToPosition),
    };
  }
  return plan;
}
