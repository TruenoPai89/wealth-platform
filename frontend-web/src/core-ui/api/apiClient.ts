/**
 * apiClient.ts
 *
 * Cliente HTTP para los endpoints REST del backend Java (Raúl).
 *
 * BASE URL: NEXT_PUBLIC_API_BASE_URL (ver .env.local.example). Por defecto
 * http://localhost:8080/api/v1 — es el puerto en el que escucha el backend core (server.port=8080 en
 * backend-core-java/src/main/resources/application.yml). La base YA incluye /api/v1: los endpoints se
 * pasan SIN ese prefijo, p.ej. "/portfolios/PF-001/rebalance".
 *
 * ENDPOINTS DISPONIBLES (Módulo 1):
 *   GET  /portfolios/{id}/rebalance          → RebalancePlan (contrato: rebalancer_base_mod1.json)
 *   POST /portfolios/{id}/rebalance/execute  → RebalanceExecution (genera asientos y transferencias)
 *   GET  /portfolios/{id}/transfers          → Transfer[]
 *   GET/PUT /portfolios/{id}/targets         → meta de balanceo del cliente
 * (/contracts/openapi-core.yaml sigue vacío: el contrato vigente es rebalancer_base_mod1.json y su
 * espejo TypeScript en core-ui/types/rebalancePlan.ts.)
 *
 * @module core-ui/api/apiClient
 */

import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";

const BASE_URL =
  process.env["NEXT_PUBLIC_API_BASE_URL"] ?? "http://localhost:8080/api/v1";

/**
 * Wrapper de fetch tipado. Lanza un error descriptivo si la respuesta no es 2xx.
 */
async function request<T>(
  endpoint: string,
  options?: RequestInit,
): Promise<T> {
  const url = `${BASE_URL}${endpoint}`;

  let response: Response;
  try {
    response = await fetch(url, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        // Authorization: `Bearer ${getAuthToken()}`, // TODO: activar con auth
        ...options?.headers,
      },
    });
  } catch {
    // fetch solo rechaza por red/CORS: backend caído, puerto equivocado u origen no permitido.
    throw new Error(
      `[apiClient] No se pudo conectar con el backend (${url}). ` +
        "¿Está arrancado y permite el origen http://localhost:3000 (CORS)?",
    );
  }

  if (!response.ok) {
    // El backend responde { "error": "..." } en 400/404/409: lo mostramos tal cual si existe.
    let detail = "";
    try {
      const body = (await response.json()) as { error?: string };
      detail = body.error ? ` — ${body.error}` : "";
    } catch {
      /* cuerpo no JSON: nos quedamos con el status */
    }
    throw new Error(
      `[apiClient] Error ${response.status} ${response.statusText} → ${url}${detail}`,
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

  put: <T>(
    endpoint: string,
    body: unknown,
    options?: Omit<RequestInit, "method" | "body">,
  ) =>
    request<T>(endpoint, {
      ...options,
      method: "PUT",
      body: JSON.stringify(body),
    }),
};

// ─── Helpers de dominio ──────────────────────────────────────────────────────

export const portfolioApi = {
  /**
   * GET /api/v1/portfolios/{portfolioId}/rebalance
   * Devuelve el plan de rebalanceo completo (RebalancePlan, schemaVersion 1.1).
   */
  getRebalancePlan: (portfolioId: string) =>
    apiClient.get<RebalancePlan>(
      `/portfolios/${encodeURIComponent(portfolioId)}/rebalance`,
    ),
};
