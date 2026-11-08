/**
 * taxService.ts — Servicio del Módulo 2 (Optimizador Fiscal)
 *
 * Llama al backend Java:
 *   GET /api/v1/tax/{portfolioId}/estimate?isin=&qty=&price=
 *   GET /api/v1/tax/{portfolioId}/analyze/{isin}?qty=&price=
 *
 * Resiliencia: si el Módulo 2 no está disponible, las funciones devuelven
 * null en lugar de propagar el error — la tabla del Módulo 1 sigue funcionando.
 *
 * @module core-ui/services/taxService
 */

import { apiClient } from "@/core-ui/api/apiClient";
import type {
  FrictionCost,
  TaxAnalysisResult,
  PositionTaxSummary,
} from "@/core-ui/types/taxOptimizer";
import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";

// ─── Helpers internos ─────────────────────────────────────────────────────────

function buildEstimateUrl(
  portfolioId: string,
  isin: string,
  qty: number,
  price: number,
): string {
  const params = new URLSearchParams({
    isin,
    qty: qty.toString(),
    price: price.toString(),
  });
  return `/tax/${encodeURIComponent(portfolioId)}/estimate?${params.toString()}`;
}

function buildAnalyzeUrl(
  portfolioId: string,
  isin: string,
  qty: number,
  price: number,
): string {
  const params = new URLSearchParams({
    qty: qty.toString(),
    price: price.toString(),
  });
  return `/tax/${encodeURIComponent(portfolioId)}/analyze/${encodeURIComponent(isin)}?${params.toString()}`;
}

// ─── API pública ──────────────────────────────────────────────────────────────

/**
 * Estima el coste fiscal de una orden de venta.
 * Devuelve null si el Módulo 2 no está disponible (modo degradado).
 */
export async function estimateFrictionCost(
  portfolioId: string,
  isin: string,
  quantity: number,
  price: number,
): Promise<FrictionCost | null> {
  try {
    return await apiClient.get<FrictionCost>(
      buildEstimateUrl(portfolioId, isin, quantity, price),
    );
  } catch {
    return null;
  }
}

/**
 * Análisis FIFO completo con detalle de lotes y clasificación OpenJEV.
 * Devuelve null si el Módulo 2 no está disponible.
 */
export async function analyzeTaxImpact(
  portfolioId: string,
  isin: string,
  quantity: number,
  price: number,
): Promise<TaxAnalysisResult | null> {
  try {
    return await apiClient.get<TaxAnalysisResult>(
      buildAnalyzeUrl(portfolioId, isin, quantity, price),
    );
  } catch {
    return null;
  }
}

/**
 * Enriquece todas las posiciones con orden de venta del plan de rebalanceo
 * con sus datos fiscales del Módulo 2.
 *
 * Las llamadas se hacen en paralelo (Promise.allSettled) para que un fallo
 * individual no bloquee el resto.
 *
 * @param plan     Plan de rebalanceo del Módulo 1.
 * @param portfolioId  Identificador de la cartera.
 * @returns  Mapa instrumentId → PositionTaxSummary.
 */
export async function fetchTaxSummariesForPlan(
  plan: RebalancePlan,
  portfolioId: string,
): Promise<Map<string, PositionTaxSummary>> {
  // Solo las posiciones con orden SELL tienen impacto fiscal que calcular
  const sellPositions = plan.positions.filter(
    (p) => p.order?.side === "SELL" && p.isin,
  );

  const results = await Promise.allSettled(
    sellPositions.map(async (pos) => {
      const isin = pos.isin!;
      const frictionCost = await estimateFrictionCost(
        portfolioId,
        isin,
        pos.order!.quantity,
        pos.order!.price,
      );
      return { instrumentId: pos.instrumentId, frictionCost, error: false };
    }),
  );

  const map = new Map<string, PositionTaxSummary>();
  results.forEach((result, i) => {
    const instrumentId = sellPositions[i]!.instrumentId;
    if (result.status === "fulfilled") {
      map.set(instrumentId, result.value);
    } else {
      map.set(instrumentId, {
        instrumentId,
        frictionCost: null,
        error: true,
      });
    }
  });

  return map;
}

// ─── Mock local (usado cuando el backend Módulo 2 no está disponible) ─────────

/**
 * Genera datos fiscales simulados para desarrollo cuando el backend Módulo 2
 * no está disponible. Activo cuando NEXT_PUBLIC_USE_MOCK=true.
 *
 * Los flags `taxWarning2Month` del mock de custody_positions.json ya tienen
 * datos realistas (IE00B3F81R35 con advertencia activa), así que el mock
 * simula el caso más interesante para testing visual.
 */
export function buildMockTaxSummaries(
  plan: RebalancePlan,
): Map<string, PositionTaxSummary> {
  const map = new Map<string, PositionTaxSummary>();

  plan.positions.forEach((pos) => {
    if (pos.order?.side !== "SELL" || !pos.isin) return;

    const gainLoss =
      (pos.order.price - (pos.costBasis ?? 0) / (pos.quantity || 1)) *
      pos.order.quantity;

    const isLoss = gainLoss < 0;

    map.set(pos.instrumentId, {
      instrumentId: pos.instrumentId,
      frictionCost: {
        taxableGainLoss: Math.round(gainLoss * 100) / 100,
        estimatedTaxCost: isLoss ? 0 : Math.round(gainLoss * 0.19 * 100) / 100,
        affectedLots: 1,
        twoMonthRuleViolation: false,
        warning: null,
      },
      error: false,
    });
  });

  return map;
}
