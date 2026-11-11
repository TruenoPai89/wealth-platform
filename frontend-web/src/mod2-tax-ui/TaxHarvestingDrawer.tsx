"use client";

/**
 * TaxHarvestingDrawer.tsx — Módulo 2: Optimizador Fiscal
 *
 * Panel lateral (Drawer) que muestra el análisis fiscal completo del plan de
 * rebalanceo: impuestos estimados por cada venta, oportunidades de tax-loss
 * harvesting y advertencias de la regla de los 2 meses (art. 33.5 LIRPF).
 *
 * Se abre desde el botón "Análisis Fiscal" del RebalanceDashboard.
 * NO tiene dependencias de Módulo 1 — recibe los datos ya procesados
 * por TaxModuleBootstrap.tsx.
 *
 * @module mod2-tax-ui/TaxHarvestingDrawer
 */

import React from "react";
import type { AssetPosition } from "@/core-ui/types/portfolio";
import { formatEur } from "@/core-ui/format";

// ─── Props ────────────────────────────────────────────────────────────────────

export interface TaxHarvestingDrawerProps {
  /** Posiciones con orden de SELL que tienen datos fiscales del Módulo 2 */
  sellPositions: AssetPosition[];
  /** true mientras se cargan los datos fiscales del backend */
  isLoading: boolean;
  /** Callback para cerrar el drawer */
  onClose: () => void;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function fmtEur(v: number) {
  return formatEur(v);
}

function fmtPct(v: number) {
  const sign = v >= 0 ? "+" : "";
  return `${sign}${v.toFixed(2).replace(".", ",")} %`;
}

/** Tramos IRPF ahorro (art. 66 LIRPF) — solo para visualización */
function iRPFBracketLabel(gainLoss: number): string {
  if (gainLoss <= 0) return "Sin tributación";
  if (gainLoss <= 6000) return "19 % (tramo 1)";
  if (gainLoss <= 50000) return "hasta 21 % (tramo 2)";
  if (gainLoss <= 200000) return "hasta 23 % (tramo 3)";
  return "hasta 28 % (tramo 4)";
}

// ─── Sub-componentes ──────────────────────────────────────────────────────────

function SkeletonCard() {
  return (
    <div className="rounded-xl border border-surface-border bg-surface-raised p-4 animate-pulse">
      <div className="h-4 bg-surface-overlay rounded w-2/3 mb-3" />
      <div className="h-3 bg-surface-overlay rounded w-1/2 mb-2" />
      <div className="h-3 bg-surface-overlay rounded w-1/3" />
    </div>
  );
}

interface TaxCardProps {
  position: AssetPosition;
}

function TaxCard({ position }: TaxCardProps) {
  const tax = position.taxSummary;
  const order = position.order;

  if (!order || order.side !== "SELL") return null;

  const hasData = tax != null;
  const gainLoss = tax?.taxableGainLoss ?? 0;
  const isGain = gainLoss > 0;
  const isLoss = gainLoss < 0;
  const isViolation = tax?.twoMonthRuleViolation === true;
  const canHarvest = isLoss && !isViolation && hasData;

  return (
    <div
      className={[
        "rounded-xl border p-4 flex flex-col gap-3 transition-colors",
        isViolation
          ? "border-semantic-warning/50 bg-semantic-warning/5"
          : canHarvest
            ? "border-semantic-gain/40 bg-semantic-gain/5"
            : "border-surface-border bg-surface-raised",
      ].join(" ")}
    >
      {/* Cabecera */}
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="text-sm font-semibold text-fg leading-tight">
            {position.name}
          </p>
          <p className="text-xs text-gray-500 mt-0.5 font-mono">
            {position.isin ?? position.instrumentId}
          </p>
        </div>
        {/* Badges de estado */}
        <div className="flex flex-col items-end gap-1 shrink-0">
          {isViolation && (
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-semibold bg-semantic-warning/15 text-semantic-warning border border-semantic-warning/30">
              <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="10" /><polyline points="12 6 12 12 16 14" />
              </svg>
              Regla 2M
            </span>
          )}
          {canHarvest && (
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-semibold bg-semantic-gain/15 text-semantic-gain border border-semantic-gain/30">
              <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              Tax Harvesting
            </span>
          )}
        </div>
      </div>

      {/* Datos de la orden */}
      <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs">
        <div>
          <span className="text-gray-500">Cantidad a vender</span>
          <p className="font-mono text-fg">{order.quantity.toLocaleString("es-ES", { maximumFractionDigits: 4 })} uds</p>
        </div>
        <div>
          <span className="text-gray-500">Precio actual</span>
          <p className="font-mono text-fg">{fmtEur(order.price)}</p>
        </div>
        <div>
          <span className="text-gray-500">Importe venta</span>
          <p className="font-mono text-fg">{fmtEur(order.amount)}</p>
        </div>
        <div>
          <span className="text-gray-500">Lotes FIFO</span>
          <p className="font-mono text-fg">{hasData ? tax!.affectedLots : "—"}</p>
        </div>
      </div>

      {/* Resultado fiscal */}
      {hasData ? (
        <div className="rounded-lg bg-surface-overlay/50 px-3 py-2.5 flex flex-col gap-1.5">
          <div className="flex justify-between items-center text-xs">
            <span className="text-gray-500">Resultado fiscal FIFO</span>
            <span className={`font-mono font-semibold ${isGain ? "text-semantic-loss" : isLoss ? "text-semantic-gain" : "text-gray-400"}`}>
              {isGain ? "+" : ""}{fmtEur(gainLoss)}
            </span>
          </div>
          <div className="flex justify-between items-center text-xs">
            <span className="text-gray-500">Tramo IRPF estimado</span>
            <span className="text-gray-400">{iRPFBracketLabel(gainLoss)}</span>
          </div>
          <div className="flex justify-between items-center text-xs border-t border-surface-border pt-1.5 mt-0.5">
            <span className={`font-medium ${tax!.estimatedTaxCost > 0 ? "text-semantic-loss" : "text-gray-400"}`}>
              Impuesto estimado
            </span>
            <span className={`font-mono font-bold text-sm ${tax!.estimatedTaxCost > 0 ? "text-semantic-loss" : "text-semantic-gain"}`}>
              {tax!.estimatedTaxCost > 0 ? `-${fmtEur(tax!.estimatedTaxCost)}` : "0,00 €"}
            </span>
          </div>
        </div>
      ) : (
        <div className="rounded-lg bg-surface-overlay/30 px-3 py-2 text-xs text-gray-500 text-center">
          Datos fiscales no disponibles (Módulo 2 offline)
        </div>
      )}

      {/* Warning de regla 2 meses */}
      {isViolation && tax?.warning && (
        <div className="rounded-lg bg-semantic-warning/10 border border-semantic-warning/20 px-3 py-2 text-xs text-semantic-warning leading-relaxed">
          <p className="font-semibold mb-1">⚠ Regla de los 2 meses (Art. 33.5 LIRPF)</p>
          <p className="text-semantic-warning/80">{tax.warning}</p>
        </div>
      )}

      {/* Oportunidad de Tax-Loss Harvesting */}
      {canHarvest && (
        <div className="rounded-lg bg-semantic-gain/10 border border-semantic-gain/20 px-3 py-2 text-xs text-semantic-gain leading-relaxed">
          <p className="font-semibold mb-1">💡 Oportunidad de Tax-Loss Harvesting</p>
          <p className="text-semantic-gain/80">
            Esta minusvalía de <span className="font-mono font-semibold">{fmtEur(Math.abs(gainLoss))}</span> puede
            compensar plusvalías del ejercicio. No recompres el mismo activo en los próximos 60 días.
          </p>
        </div>
      )}
    </div>
  );
}

// ─── Componente principal ─────────────────────────────────────────────────────

export function TaxHarvestingDrawer({
  sellPositions,
  isLoading,
  onClose,
}: TaxHarvestingDrawerProps) {
  // Totales fiscales del plan
  const totalTaxCost = sellPositions.reduce(
    (acc, p) => acc + (p.taxSummary?.estimatedTaxCost ?? 0),
    0,
  );
  const totalHarvestable = sellPositions.reduce(
    (acc, p) => {
      const gl = p.taxSummary?.taxableGainLoss ?? 0;
      return acc + (gl < 0 && !p.taxSummary?.twoMonthRuleViolation ? Math.abs(gl) : 0);
    },
    0,
  );
  const violationsCount = sellPositions.filter(
    (p) => p.taxSummary?.twoMonthRuleViolation || p.flags?.taxWarning2Month,
  ).length;

  return (
    <>
      {/* Overlay */}
      <div
        className="fixed inset-0 bg-black/50 z-40 backdrop-blur-sm"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Drawer */}
      <aside
        className={[
          "fixed right-0 top-0 h-full max-h-dvh z-50",
          "w-full max-w-md",
          "bg-surface-raised border-l border-surface-border",
          "flex flex-col shadow-2xl",
          "overflow-hidden",
        ].join(" ")}
        role="dialog"
        aria-modal="true"
        aria-label="Análisis fiscal del rebalanceo"
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-surface-border shrink-0">
          <div className="flex items-center gap-2.5">
            {/* Icono fiscal */}
            <div className="w-8 h-8 rounded-lg bg-brand-500/15 flex items-center justify-center">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-brand-400" aria-hidden="true">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
                <line x1="16" y1="13" x2="8" y2="13" />
                <line x1="16" y1="17" x2="8" y2="17" />
                <polyline points="10 9 9 9 8 9" />
              </svg>
            </div>
            <div>
              <h2 className="text-sm font-semibold text-fg">Análisis Fiscal</h2>
              <p className="text-xs text-gray-500">Módulo 2 — Optimizador Fiscal</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-gray-500 hover:text-fg hover:bg-surface-overlay transition-colors"
            aria-label="Cerrar panel fiscal"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>

        {/* KPIs resumen */}
        <div className="grid grid-cols-3 gap-px bg-surface-border shrink-0">
          <div className="bg-surface-raised px-4 py-3 text-center">
            <p className="text-xs text-gray-500 mb-1">Impuesto total</p>
            <p className={`font-mono font-bold text-sm ${totalTaxCost > 0 ? "text-semantic-loss" : "text-gray-400"}`}>
              {totalTaxCost > 0 ? `-${fmtEur(totalTaxCost)}` : "0,00 €"}
            </p>
          </div>
          <div className="bg-surface-raised px-4 py-3 text-center">
            <p className="text-xs text-gray-500 mb-1">Harvesting</p>
            <p className={`font-mono font-bold text-sm ${totalHarvestable > 0 ? "text-semantic-gain" : "text-gray-400"}`}>
              {totalHarvestable > 0 ? fmtEur(totalHarvestable) : "0,00 €"}
            </p>
          </div>
          <div className="bg-surface-raised px-4 py-3 text-center">
            <p className="text-xs text-gray-500 mb-1">Alertas 2M</p>
            <p className={`font-mono font-bold text-sm ${violationsCount > 0 ? "text-semantic-warning" : "text-gray-400"}`}>
              {violationsCount > 0 ? `${violationsCount}` : "0"}
            </p>
          </div>
        </div>

        {/* Lista de posiciones */}
        <div className="flex-1 overflow-y-auto px-4 py-4 flex flex-col gap-3">
          {isLoading ? (
            <>
              <SkeletonCard />
              <SkeletonCard />
            </>
          ) : sellPositions.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 gap-3 text-center">
              <div className="w-12 h-12 rounded-full bg-surface-overlay flex items-center justify-center">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" className="text-gray-500" aria-hidden="true">
                  <circle cx="12" cy="12" r="10" /><line x1="12" y1="8" x2="12" y2="12" /><line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
              </div>
              <div>
                <p className="text-sm font-medium text-gray-400">Sin ventas en este plan</p>
                <p className="text-xs text-gray-600 mt-1">
                  El plan actual no incluye órdenes de venta que generen impacto fiscal.
                </p>
              </div>
            </div>
          ) : (
            sellPositions.map((pos) => (
              <TaxCard key={pos.instrumentId} position={pos} />
            ))
          )}
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t border-surface-border shrink-0">
          <p className="text-xs text-gray-600 text-center leading-relaxed">
            Cálculos orientativos según FIFO (art. 37.2 LIRPF) y tramos del ahorro (art. 66 LIRPF).
            Consulte a su asesor fiscal antes de ejecutar.
          </p>
        </div>
      </aside>
    </>
  );
}
