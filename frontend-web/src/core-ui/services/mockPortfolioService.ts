/**
 * mockPortfolioService.ts
 *
 * Servicio simulado que lee el JSON de /bank-simulator-mocks/.
 * El JSON está alineado con el contrato de Raúl:
 *   RebalancePlan.java → contracts/mocks-contracts/rebalancer_base_mod1.json
 *
 * CUANDO EL BACKEND ESTÉ LISTO:
 *   - Eliminar este archivo.
 *   - En RebalanceDashboard.tsx, sustituir la llamada a fetchRebalancePlan
 *     por: apiClient.get<RebalancePlan>(`/api/v1/portfolios/${portfolioId}/rebalance`)
 *   - El puerto del backend de Raúl es 8081 (ver application.yml).
 *     Definir en .env.local: NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
 *
 * @module core-ui/services/mockPortfolioService
 */

import type { RebalancePlan } from "@/core-ui/types/portfolio";
import rebalancePlanRaw from "@/core-ui/mocks/custody_positions.json";

/**
 * Simula GET /api/v1/portfolios/{portfolioId}/rebalance
 * El delay imita latencia de red para que el skeleton loader sea visible.
 */
export async function fetchRebalancePlan(
  _portfolioId?: string,
  simulatedDelayMs = 600,
): Promise<RebalancePlan> {
  await new Promise((resolve) => setTimeout(resolve, simulatedDelayMs));
  return rebalancePlanRaw as unknown as RebalancePlan;
}
