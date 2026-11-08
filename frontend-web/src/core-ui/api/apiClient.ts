/**
 * apiClient.ts
 *
 * Cliente HTTP para los endpoints REST del backend Java (Raúl).
 * Puerto: 8081 (ver backend-core-java/src/main/resources/application.yml)
 *
 * ENDPOINT DISPONIBLE (Módulo 1):
 *   GET /api/v1/portfolios/{portfolioId}/rebalance → RebalancePlan
 *
 * PARA ACTIVAR:
 *   Crear frontend-web/.env.local con:
 *     NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
 *   Luego en RebalanceDashboard.tsx reemplazar fetchRebalancePlan (mock)
 *   por: apiClient.get<RebalancePlan>(`/api/v1/portfolios/${portfolioId}/rebalance`)
 *
 * @module core-ui/api/apiClient
 */

import type { RebalancePlan } from "@/core-ui/types/portfolio";

const BASE_URL = process.env["NEXT_PUBLIC_API_BASE_URL"] ?? "";

/**
 * Wrapper de fetch tipado. Lanza un error descriptivo si la respuesta no es 2xx.
 */
async function request<T>(
  endpoint: string,
  options?: RequestInit,
): Promise<T> {
  if (!BASE_URL) {
    throw new Error(
      "[apiClient] NEXT_PUBLIC_API_BASE_URL no está definida. " +
        "Añade NEXT_PUBLIC_API_BASE_URL=http://localhost:8081 en .env.local.",
    );
  }

  const url = `${BASE_URL}${endpoint}`;

  const response = await fetch(url, {
    headers: {
      "Content-Type": "application/json",
      // Authorization: `Bearer ${getAuthToken()}`, // TODO: activar con auth
      ...options?.headers,
    },
    ...options,
  });

  if (!response.ok) {
    throw new Error(
      `[apiClient] Error ${response.status} ${response.statusText} → ${url}`,
    );
  }

  return response.json() as Promise<T>;
}

// ─── Helpers públicos ─────────────────────────────────────────────────────────

export const apiClient = {
  get: <T>(endpoint: string, options?: Omit<RequestInit, "method">) =>
    request<T>(endpoint, { ...options, method: "GET" }),

  post: <T>(
    endpoint: string,
    body: unknown,
    options?: Omit<RequestInit, "method" | "body">,
  ) =>
    request<T>(endpoint, {
      ...options,
      method: "POST",
      body: JSON.stringify(body),
    }),
};

// ─── Helpers de dominio (usar cuando el backend esté activo) ─────────────────

export const portfolioApi = {
  /**
   * GET /api/v1/portfolios/{portfolioId}/rebalance
   * Devuelve el plan de rebalanceo completo (RebalancePlan).
   */
  getRebalancePlan: (portfolioId: string) =>
    apiClient.get<RebalancePlan>(`/api/v1/portfolios/${portfolioId}/rebalance`),
};
