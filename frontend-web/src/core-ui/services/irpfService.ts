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
 * Con NEXT_PUBLIC_USE_MOCK=true todos los endpoints devuelven datos simulados.
 *
 * @module core-ui/services/irpfService
 */

import { apiClient } from "@/core-ui/api/apiClient";
import { USE_MOCK } from "@/core-ui/services/portfolioService";
import type {
  AnnualReport,
  AnnualReportRequest,
  IrpfExplanation,
  IrpfSaleRequest,
  IrpfSaleResponse,
} from "@/core-ui/types/irpf";
import irpfSaleMockData from "@/core-ui/mocks/irpf_sale_mock.json";
import irpfAnnualMockData from "@/core-ui/mocks/irpf_annual_mock.json";

const AI_BASE_URL =
  process.env["NEXT_PUBLIC_AI_BASE_URL"] ?? "http://localhost:8000";

// ─── Simulador de venta ────────────────────────────────────────────────────────

export async function simulateSale(
  portfolioId: string,
  request: IrpfSaleRequest,
): Promise<IrpfSaleResponse> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 600));
    const mockBase = irpfSaleMockData as IrpfSaleResponse;
    const costPerUnit = 61.2; // coste medio mock del portfolio demo
    const gainLoss = (request.salePrice - costPerUnit) * request.quantity;
    const tax = gainLoss > 0 ? gainLoss * 0.19 : 0;
    const beforeTax =
      (request.situation.realizedGains ?? 0) > 0
        ? (request.situation.realizedGains ?? 0) * 0.19
        : 0;
    return {
      ...mockBase,
      isin: request.isin,
      quantity: request.quantity,
      salePrice: request.salePrice,
      saleGainLoss: Math.round(gainLoss * 100) / 100,
      before: { ...mockBase.before, tax: Math.round(beforeTax * 100) / 100 },
      after: {
        ...mockBase.after,
        tax: Math.round((beforeTax + tax) * 100) / 100,
      },
      taxDelta: Math.round(tax * 100) / 100,
    };
  }
  return apiClient.post<IrpfSaleResponse>(
    `/irpf/${encodeURIComponent(portfolioId)}/simulate-sale`,
    request,
  );
}

export async function explainSale(
  result: IrpfSaleResponse,
): Promise<IrpfExplanation | null> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 400));
    const signo = result.saleGainLoss >= 0 ? "ganancia" : "pérdida";
    const importe = Math.abs(result.saleGainLoss).toFixed(2);
    const fiscal =
      result.taxDelta > 0
        ? `El impacto fiscal estimado es de ${result.taxDelta.toFixed(2)} € adicionales en la base del ahorro (19–28 % según LIRPF art. 66).`
        : "Esta venta no genera coste fiscal adicional.";
    return {
      texto: `Simulación orientativa: si vendes ${result.quantity} participaciones a ${result.salePrice} €, obtienes una ${signo} de ${importe} €. ${fiscal}`,
      fuente: "plantilla",
      intentos: 1,
    };
  }
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
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 800));
    return irpfAnnualMockData as AnnualReport;
  }
  return apiClient.post<AnnualReport>(
    `/irpf/${encodeURIComponent(portfolioId)}/annual-report`,
    request,
  );
}

export async function explainAnnualReport(
  report: AnnualReport,
): Promise<IrpfExplanation | null> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 400));
    const diferidas =
      report.deferredLosses < 0
        ? ` Atención: ${Math.abs(report.deferredLosses).toFixed(2)} € de pérdidas diferidas por la regla de los 2 meses (Art. 33.5 LIRPF) que no son computables este ejercicio.`
        : "";
    return {
      texto: `Resumen fiscal ${report.fiscalYear}: ganancias computables ${report.totalGains.toFixed(2)} €, pérdidas computables ${Math.abs(report.totalLosses).toFixed(2)} €. Saldo neto: ${report.result.netBalance.toFixed(2)} €. Base imponible del ahorro: ${report.result.taxableBase.toFixed(2)} €. Cuota estimada: ${report.result.tax.toFixed(2)} €.${diferidas}`,
      fuente: "plantilla",
      intentos: 1,
    };
  }
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
