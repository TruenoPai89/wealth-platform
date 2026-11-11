"use client";

/**
 * TaxDashboard.tsx — Módulo 2: Optimizador Fiscal
 *
 * Página dedicada al análisis fiscal de la cartera.
 * Muestra todas las posiciones con sus datos FIFO, alertas de la regla
 * de los 2 meses y oportunidades de tax-loss harvesting.
 *
 * @module mod2-tax-ui/TaxDashboard
 */

import React, { useCallback, useEffect, useMemo, useState } from "react";
import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";
import type { AssetPosition } from "@/core-ui/types/portfolio";
import { loadPortfolio, USE_MOCK } from "@/core-ui/services/portfolioService";
import {
  fetchTaxSummariesForPlan,
  buildMockTaxSummaries,
} from "@/core-ui/services/taxService";
import { formatEur } from "@/core-ui/format";
import { TwoMonthWarningBadge } from "@/mod2-tax-ui/TwoMonthWarningBadge";

// ─── Props ────────────────────────────────────────────────────────────────────

interface TaxDashboardProps {
  portfolioId?: string;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function fmtEur(v: number) {
  return formatEur(v);
}

function iRPFBracketLabel(gainLoss: number): string {
  if (gainLoss <= 0) return "Sin tributación";
  if (gainLoss <= 6000) return "19 %";
  if (gainLoss <= 50000) return "≤ 21 %";
  if (gainLoss <= 200000) return "≤ 23 %";
  return "≤ 28 %";
}

// ─── Sub-componentes ──────────────────────────────────────────────────────────

function SkeletonRow() {
  return (
    <tr className="border-b border-surface-border animate-pulse">
      {[1, 2, 3, 4, 5].map((i) => (
        <td key={i} className="px-4 py-4">
          <div className="h-3 bg-surface-overlay rounded w-3/4" />
        </td>
      ))}
    </tr>
  );
}

interface PositionRowProps {
  position: AssetPosition;
  isEven: boolean;
}

function TaxPositionRow({ position, isEven }: PositionRowProps) {
  const tax = position.taxSummary;
  const order = position.order;
  const isSell = order?.side === "SELL";

  const gainLoss = tax?.taxableGainLoss ?? null;
  const isGain = gainLoss !== null && gainLoss > 0;
  const isLoss = gainLoss !== null && gainLoss < 0;
  const isViolation = tax?.twoMonthRuleViolation === true;
  const canHarvest = isLoss && !isViolation && tax != null;

  return (
    <tr
      className={[
        "border-b border-surface-border transition-colors",
        isEven ? "bg-surface-raised" : "bg-surface-base",
        "hover:bg-surface-overlay/40",
        isViolation ? "border-l-2 border-l-semantic-warning" : "",
        canHarvest ? "border-l-2 border-l-semantic-gain" : "",
      ].join(" ")}
    >
      {/* Activo */}
      <td className="px-4 py-3">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-sm font-medium text-fg">{position.name}</span>
            {/* Slot manual del badge — en esta página no usamos SlotRegistry */}
            <TwoMonthWarningBadge position={position} />
            {canHarvest && (
              <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs font-semibold bg-semantic-gain/15 text-semantic-gain border border-semantic-gain/30">
                💡 Harvesting
              </span>
            )}
          </div>
          <span className="text-xs text-gray-500 font-mono">
            {position.isin ?? position.instrumentId}
          </span>
        </div>
      </td>

      {/* Orden */}
      <td className="px-4 py-3 text-sm text-center">
        {isSell ? (
          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-semantic-loss/15 text-semantic-loss border border-semantic-loss/30">
            VENTA
          </span>
        ) : order?.side === "BUY" ? (
          <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-blue-500/15 text-blue-400 border border-blue-500/30">
            COMPRA
          </span>
        ) : (
          <span className="text-gray-600 text-xs">Sin orden</span>
        )}
      </td>

      {/* Resultado fiscal FIFO */}
      <td className="px-4 py-3 text-right font-mono text-sm">
        {gainLoss !== null ? (
          <span className={isGain ? "text-semantic-loss" : isLoss ? "text-semantic-gain" : "text-gray-400"}>
            {isGain ? "+" : ""}{fmtEur(gainLoss)}
          </span>
        ) : (
          <span className="text-gray-600">—</span>
        )}
      </td>

      {/* Tramo IRPF */}
      <td className="px-4 py-3 text-center text-xs text-gray-400">
        {gainLoss !== null ? iRPFBracketLabel(gainLoss) : "—"}
      </td>

      {/* Impuesto estimado */}
      <td className="px-4 py-3 text-right font-mono text-sm">
        {tax ? (
          <span className={tax.estimatedTaxCost > 0 ? "text-semantic-loss font-semibold" : "text-semantic-gain"}>
            {tax.estimatedTaxCost > 0 ? `-${fmtEur(tax.estimatedTaxCost)}` : "0,00 €"}
          </span>
        ) : (
          <span className="text-gray-600">—</span>
        )}
      </td>
    </tr>
  );
}

// ─── Componente principal ─────────────────────────────────────────────────────

export function TaxDashboard({ portfolioId = "PF-001" }: TaxDashboardProps) {
  const [plan, setPlan] = useState<RebalancePlan | null>(null);
  const [enrichedPositions, setEnrichedPositions] = useState<AssetPosition[] | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const loaded = await loadPortfolio(portfolioId);
      setPlan(loaded);

      // Cargar datos fiscales del Módulo 2
      const taxMap = USE_MOCK
        ? buildMockTaxSummaries(loaded)
        : await fetchTaxSummariesForPlan(loaded, portfolioId);

      const enriched: AssetPosition[] = loaded.positions.map((pos) => {
        const summary = taxMap.get(pos.instrumentId);
        if (!summary || summary.error || !summary.frictionCost) return pos as AssetPosition;
        return {
          ...pos,
          taxSummary: {
            taxableGainLoss: summary.frictionCost.taxableGainLoss,
            estimatedTaxCost: summary.frictionCost.estimatedTaxCost,
            affectedLots: summary.frictionCost.affectedLots,
            twoMonthRuleViolation: summary.frictionCost.twoMonthRuleViolation,
            warning: summary.frictionCost.warning,
          },
        } as AssetPosition;
      });

      setEnrichedPositions(enriched);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Error cargando datos fiscales.");
    } finally {
      setIsLoading(false);
    }
  }, [portfolioId]);

  useEffect(() => { void load(); }, [load]);

  const positions = useMemo(
    () => (enrichedPositions ?? (plan?.positions as AssetPosition[] | undefined) ?? []),
    [enrichedPositions, plan],
  );

  // KPIs agregados
  const totalTax = positions.reduce((acc, p) => acc + (p.taxSummary?.estimatedTaxCost ?? 0), 0);
  const totalHarvestable = positions.reduce((acc, p) => {
    const gl = p.taxSummary?.taxableGainLoss ?? 0;
    return acc + (gl < 0 && !p.taxSummary?.twoMonthRuleViolation ? Math.abs(gl) : 0);
  }, 0);
  const violations = positions.filter(
    (p) => p.taxSummary?.twoMonthRuleViolation || p.flags?.taxWarning2Month,
  ).length;
  const sellCount = positions.filter((p) => p.order?.side === "SELL").length;

  return (
    <div className="flex flex-col gap-6">

      {/* Cabecera */}
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div>
          <h1 className="text-xl font-bold text-fg">Optimizador Fiscal</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Análisis FIFO · Regla de los 2 meses (Art. 33.5 LIRPF) · Tax-Loss Harvesting
          </p>
        </div>
        <button
          onClick={() => void load()}
          disabled={isLoading}
          className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium border border-surface-border text-gray-400 hover:text-fg hover:border-gray-500 disabled:opacity-50 transition-colors"
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={isLoading ? "animate-spin" : ""} aria-hidden="true">
            <polyline points="23 4 23 10 17 10" /><polyline points="1 20 1 14 7 14" />
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
          </svg>
          Actualizar
        </button>
      </div>

      {/* KPIs */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: "Órdenes de venta", value: `${sellCount}`, color: "text-fg" },
          { label: "Impuesto estimado", value: totalTax > 0 ? `-${fmtEur(totalTax)}` : "0,00 €", color: totalTax > 0 ? "text-semantic-loss" : "text-gray-400" },
          { label: "Harvesting potencial", value: totalHarvestable > 0 ? fmtEur(totalHarvestable) : "0,00 €", color: totalHarvestable > 0 ? "text-semantic-gain" : "text-gray-400" },
          { label: "Alertas Regla 2M", value: `${violations}`, color: violations > 0 ? "text-semantic-warning" : "text-gray-400" },
        ].map((kpi) => (
          <div key={kpi.label} className="rounded-xl border border-surface-border bg-surface-raised px-4 py-4">
            <p className="text-xs text-gray-500 mb-1.5">{kpi.label}</p>
            <p className={`text-2xl font-bold font-mono ${kpi.color}`}>{kpi.value}</p>
          </div>
        ))}
      </div>

      {/* Error */}
      {error && (
        <div className="rounded-xl border border-semantic-loss/40 bg-semantic-loss/10 px-5 py-4">
          <p className="text-semantic-loss text-sm font-medium">Error cargando datos fiscales</p>
          <p className="text-semantic-loss/70 text-xs mt-1">{error}</p>
        </div>
      )}

      {/* Tabla */}
      <div className="rounded-xl border border-surface-border bg-surface-raised overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm" aria-label="Análisis fiscal de posiciones">
            <thead>
              <tr className="border-b border-surface-border bg-surface-overlay/50">
                <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">Activo</th>
                <th className="px-4 py-3 text-center text-xs font-semibold text-gray-500 uppercase tracking-wider">Orden</th>
                <th className="px-4 py-3 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">Resultado FIFO</th>
                <th className="px-4 py-3 text-center text-xs font-semibold text-gray-500 uppercase tracking-wider">Tramo IRPF</th>
                <th className="px-4 py-3 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">Impuesto estimado</th>
              </tr>
            </thead>
            <tbody>
              {isLoading
                ? Array.from({ length: 5 }).map((_, i) => <SkeletonRow key={i} />)
                : positions.map((pos, i) => (
                    <TaxPositionRow key={pos.instrumentId} position={pos} isEven={i % 2 === 0} />
                  ))}
            </tbody>
          </table>
        </div>

        {!isLoading && positions.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 gap-2">
            <p className="text-gray-500 font-medium">No hay posiciones registradas</p>
          </div>
        )}
      </div>

      {/* Disclaimer legal */}
      <p className="text-xs text-gray-600 text-center px-4">
        Cálculos orientativos según FIFO (art. 37.2 LIRPF) y tramos del ahorro (art. 66 LIRPF).
        Consulte a su asesor fiscal antes de ejecutar cualquier operación.
      </p>
    </div>
  );
}
