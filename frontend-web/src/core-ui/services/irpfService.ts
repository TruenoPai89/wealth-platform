/**
 * irpfService.ts — Servicio del Módulo 3 (simulador IRPF)
 *
 *   POST {backend Java}/irpf/{portfolioId}/simulate-sale  → cálculo (determinista)
 *   POST {servicio Python}/explain/irpf-sale             → explicación con Claude
 *
 * La explicación es opcional: si el servicio Python está caído, se devuelve null y la
 * pantalla sigue mostrando las cifras.
 *
 * @module core-ui/services/irpfService
 */

import { apiClient } from "@/core-ui/api/apiClient";
import type {
  IrpfExplanation,
  IrpfSaleRequest,
  IrpfSaleResponse,
} from "@/core-ui/types/irpf";

const AI_BASE_URL =
  process.env["NEXT_PUBLIC_AI_BASE_URL"] ?? "http://localhost:8000";

export async function simulateSale(
  portfolioId: string,
  request: IrpfSaleRequest,
): Promise<IrpfSaleResponse> {
  return apiClient.post<IrpfSaleResponse>(
    `/irpf/${encodeURIComponent(portfolioId)}/simulate-sale`,
    request,
  );
}

export async function explainSale(
  result: IrpfSaleResponse,
): Promise<IrpfExplanation | null> {
  try {
    const response = await fetch(`${AI_BASE_URL}/explain/irpf-sale`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(result),
    });
    if (!response.ok) return null;
    return (await response.json()) as IrpfExplanation;
  } catch {
    return null; // servicio Python caído: modo degradado
  }
}
