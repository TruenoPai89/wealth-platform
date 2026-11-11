/**
 * marginService.ts
 * Servicio del análisis de margin call (Módulo 4): backend Java para el cálculo, servicio Python para la
 * explicación en lenguaje natural.
 */

import { apiClient } from "@/core-ui/api/apiClient";
import { aiRequest } from "@/core-ui/services/pledgeService";

import type {
  ApiEnvelope,
  MarginRiskAnalysis,
  PledgeConfigRequest,
} from "@/core-ui/types/margin";
import type { Explanation } from "@/core-ui/types/pledge";

/**
 * POST /api/v1/margin/analyze. Cálculo sin efectos: no guarda nada en el backend.
 * Lanza un Error con el mensaje del backend si la configuración no es válida (400).
 */
export async function analyzeMargin(
  config: PledgeConfigRequest,
): Promise<MarginRiskAnalysis> {
  const envelope = await apiClient.post<ApiEnvelope<MarginRiskAnalysis>>(
    "/margin/analyze",
    config,
  );

  if (envelope.error || !envelope.data) {
    throw new Error(envelope.error ?? "El backend no devolvió el análisis de margin call.");
  }
  return envelope.data;
}

/**
 * Pide al servicio Python la explicación del análisis. Si falla, devuelve null: el resto del informe
 * (cifras, estrés, recomendaciones) viene del backend y sigue siendo válido.
 */
export async function explainMargin(
  analysis: MarginRiskAnalysis,
): Promise<Explanation | null> {
  try {
    return await aiRequest<Explanation>("/explain/margin-analysis", {
      method: "POST",
      body: JSON.stringify(analysis),
    });
  } catch {
    return null;
  }
}
