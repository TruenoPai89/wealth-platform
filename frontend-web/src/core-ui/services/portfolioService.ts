/**
 * portfolioService.ts
 *
 * Punto único de datos del Módulo 1 para la UI:
 *   - REAL (por defecto): GET {API_BASE}/portfolios/{id}/rebalance → backend Java (RebalancePlan v1.1).
 *   - MOCK (opt-in): NEXT_PUBLIC_USE_MOCK=true usa mockPortfolioService (custody_positions.json, schema 1.0),
 *     útil para trabajar en la UI sin backend. El mock no trae coste/P&L ni proyección, y las acciones que
 *     escriben (ejecutar, editar meta) se desactivan en ese modo.
 *
 * @module core-ui/services/portfolioService
 */

import { apiClient } from "@/core-ui/api/apiClient";
import { fetchRebalancePlan as fetchMockPlan } from "@/core-ui/services/mockPortfolioService";
import {
  getMockTransfers,
  addMockRebalanceTransfers,
  settleMockTransfers,
} from "@/core-ui/services/mockTransferStore";
import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";
import type { RebalanceExecution, Transfer } from "@/core-ui/types/transfers";
import type {
  Basket,
  ContributionExecution,
  ContributionPlan,
} from "@/core-ui/types/baskets";

export const USE_MOCK = process.env["NEXT_PUBLIC_USE_MOCK"] === "true";

/** Agrupaciones (cestas) de fondos de la cartera. */
export function fetchBaskets(portfolioId: string): Promise<Basket[]> {
  return apiClient.get<Basket[]>(`/portfolios/${encodeURIComponent(portfolioId)}/baskets`);
}

/** Vista previa SIN efectos: qué se compraría de cada fondo con `amount` euros (solo compras). */
export function previewContribution(
  portfolioId: string,
  basketId: string,
  amount: number,
): Promise<ContributionPlan> {
  return apiClient.post<ContributionPlan>(
    `/portfolios/${encodeURIComponent(portfolioId)}/baskets/${encodeURIComponent(basketId)}/contribution/preview`,
    { amount },
  );
}

/**
 * Invierte la aportación en la agrupación: el backend recalcula, genera los asientos y registra la Aportación y una
 * Compra por fondo. 400 si el importe no es válido; 404 si la cesta no existe.
 */
export function executeContribution(
  portfolioId: string,
  basketId: string,
  amount: number,
): Promise<ContributionExecution> {
  return apiClient.post<ContributionExecution>(
    `/portfolios/${encodeURIComponent(portfolioId)}/baskets/${encodeURIComponent(basketId)}/contribution`,
    { amount },
  );
}

/** Llama al endpoint real y devuelve el plan tal cual. */
export function fetchRebalancePlan(portfolioId: string): Promise<RebalancePlan> {
  return apiClient.get<RebalancePlan>(
    `/portfolios/${encodeURIComponent(portfolioId)}/rebalance`,
  );
}

/**
 * Guarda la meta de balanceo del cliente (instrumentId → peso 0..1, suma 1).
 * El backend valida y responde 400 con { error } si no es válida. La nueva meta se usa en el siguiente rebalanceo.
 */
export function updateTargets(
  portfolioId: string,
  weights: Record<string, number>,
): Promise<Record<string, number>> {
  return apiClient.put<Record<string, number>>(
    `/portfolios/${encodeURIComponent(portfolioId)}/targets`,
    weights,
  );
}

/**
 * Acepta el plan vigente: el backend recalcula el plan, genera los asientos y registra las transferencias
 * (compras, ventas y ajuste neto de efectivo). Responde 409 si no hay nada que ejecutar.
 */
export function executeRebalance(portfolioId: string): Promise<RebalanceExecution> {
  return apiClient.post<RebalanceExecution>(
    `/portfolios/${encodeURIComponent(portfolioId)}/rebalance/execute`,
    {},
  );
}

/** Historial de movimientos de la cartera, el más reciente primero. */
export function fetchTransfers(portfolioId: string): Promise<Transfer[]> {
  if (USE_MOCK) {
    // Devuelve el store en memoria: empieza vacío y crece con cada rebalanceo simulado
    return Promise.resolve(getMockTransfers());
  }
  return apiClient.get<Transfer[]>(
    `/portfolios/${encodeURIComponent(portfolioId)}/transfers`,
  );
}

/**
 * Simula la ejecución del rebalanceo en modo mock:
 * genera las transferencias en el store de sesión y las devuelve
 * con el mismo contrato que el backend real (RebalanceExecution).
 */
export function mockExecuteRebalance(plan: RebalancePlan): RebalanceExecution {
  const transfers = addMockRebalanceTransfers(
    plan.portfolioId,
    plan.orders,
    plan.cashFlow.netCashFlow,
  );
  // Simula liquidación T+2 después de 3 segundos para que el badge "En proceso" sea visible
  setTimeout(() => settleMockTransfers(), 3000);

  return {
    executionId: transfers[0]?.executionId ?? "MOCK-EXEC-0001",
    portfolioId: plan.portfolioId,
    executedAt: new Date().toISOString(),
    journalReference: `JNL-REF-${Date.now()}`,
    transfers,
    cashFlow: plan.cashFlow,
  };
}

/**
 * Carga el plan de la cartera: backend real salvo que se active el modo mock.
 * El mock es schema 1.0 (sin performance/projectedPositions): se tipa como v1.1 y el resto del código
 * trata esos campos como ausentes (ver rebalancePlanAdapter.toDisplayPlan).
 */
export async function loadPortfolio(portfolioId: string): Promise<RebalancePlan> {
  if (USE_MOCK) {
    return (await fetchMockPlan(portfolioId)) as unknown as RebalancePlan;
  }
  return fetchRebalancePlan(portfolioId);
}
