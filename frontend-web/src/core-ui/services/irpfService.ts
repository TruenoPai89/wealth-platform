/**
 * irpfService.ts — Servicio del Módulo 3 (simulador IRPF)
 *
 *   POST {backend Java}/irpf/{portfolioId}/simulate-sale  → cálculo (determinista)
 *   POST {servicio Python}/explain/irpf-sale             → explicación con Claude
 *   POST {backend Java}/irpf/{portfolioId}/annual-report → resumen fiscal del ejercicio
 *   POST {servicio Python}/explain/irpf-annual           → explicación del resumen
 *
 * La explicación es opcional: si el servicio Python está caído, se devuelve null y la
 * pantalla sigue mostrando las cifras.
 *
 * @module core-ui/services/irpfService
 */

import { apiClient } from "@/core-ui/api/apiClient";
import type {
  AnnualReport,
  AnnualReportRequest,
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

// ─── Resumen fiscal del ejercicio ──────────────────────────────────────────────

export async function buildAnnualReport(
  portfolioId: string,
  request: AnnualReportRequest,
): Promise<AnnualReport> {
  return apiClient.post<AnnualReport>(
    `/irpf/${encodeURIComponent(portfolioId)}/annual-report`,
    request,
  );
}

export async function explainAnnualReport(
  report: AnnualReport,
): Promise<IrpfExplanation | null> {
  try {
    const response = await fetch(`${AI_BASE_URL}/explain/irpf-annual`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(report),
    });
    if (!response.ok) return null;
    return (await response.json()) as IrpfExplanation;
  } catch {
    return null; // servicio Python caído: modo degradado
  }
}
