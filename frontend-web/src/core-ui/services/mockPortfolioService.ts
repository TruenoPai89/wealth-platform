/**
 * mockPortfolioService.ts
 *
 * Servicio simulado que lee los JSON de /bank-simulator-mocks/.
 * En Next.js App Router, esta importación funciona en Server Components
 * porque `resolveJsonModule: true` está en tsconfig.
 *
 * CUANDO EL BACKEND ESTÉ LISTO:
 *   - Eliminar este archivo.
 *   - Reemplazar los imports en los componentes por llamadas a apiClient.ts.
 *   - apiClient.ts ya tiene la estructura para hacer fetch a los endpoints de Raúl.
 *
 * @module core-ui/services/mockPortfolioService
 */

import type { CustodyPositionsResponse } from "@/core-ui/types/portfolio";

// Importación directa del JSON mock (funciona gracias a resolveJsonModule en tsconfig)
// El path relativo sale de src/ hacia la raíz del monorepo.
// En producción, Next.js se ejecuta desde /frontend-web, así que ../../../ llega a la raíz.
import custodyPositionsRaw from "../../../../bank-simulator-mocks/api-json-responses/custody_positions.json";

/**
 * Simula una llamada GET /api/portfolios/{portfolioId}/positions
 *
 * El delay artificial imita latencia de red para que el skeleton loader
 * sea visible durante el desarrollo.
 */
export async function fetchCustodyPositions(
  _portfolioId?: string,
  simulatedDelayMs = 600,
): Promise<CustodyPositionsResponse> {
  await new Promise((resolve) => setTimeout(resolve, simulatedDelayMs));

  // Cast tipado — el JSON está construido para satisfacer la interfaz exacta.
  return custodyPositionsRaw as CustodyPositionsResponse;
}
