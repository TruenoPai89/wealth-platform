"use client";

/**
 * TaxModuleBootstrap.tsx — Módulo 2: Optimizador Fiscal
 *
 * Punto de entrada del Módulo 2 en el árbol React. Este componente:
 *
 *   1. REGISTRA el slot: inyecta `TwoMonthWarningBadge` en "StatusBadgeSlot"
 *      de la tabla de posiciones — sin tocar PositionsTableWithSlots.tsx.
 *
 *   2. CARGA los datos fiscales: llama al backend Módulo 2
 *      (GET /api/v1/tax/{portfolioId}/estimate) para cada orden SELL del plan
 *      y enriquece las posiciones con `taxSummary`.
 *      Si el Módulo 2 no está disponible, opera en modo degradado (sin datos).
 *
 *   3. EXPONE el botón y el Drawer de Tax Harvesting al padre mediante callbacks.
 *
 * CÓMO SE MONTA:
 *   En RebalanceDashboard.tsx, dentro del bloque "Punto de extensión":
 *     <TaxModuleBootstrap
 *       plan={plan}
 *       portfolioId={portfolioId}
 *       onPositionsEnriched={setEnrichedPositions}
 *     />
 *
 * @module mod2-tax-ui/TaxModuleBootstrap
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import { useRegisterSlot, type SlotComponent } from "@/core-ui/SlotRegistry";
import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";
import type { AssetPosition } from "@/core-ui/types/portfolio";
import {
  fetchTaxSummariesForPlan,
  buildMockTaxSummaries,
} from "@/core-ui/services/taxService";
import { USE_MOCK } from "@/core-ui/services/portfolioService";
import { TwoMonthWarningBadge } from "@/mod2-tax-ui/TwoMonthWarningBadge";
import { TaxHarvestingDrawer } from "@/mod2-tax-ui/TaxHarvestingDrawer";

// ─── Props ────────────────────────────────────────────────────────────────────

export interface TaxModuleBootstrapProps {
  /** Plan de rebalanceo del Módulo 1 (null mientras carga). */
  plan: RebalancePlan | null;
  /** ID de la cartera activa. */
  portfolioId: string;
  /**
   * Callback que el padre usa para obtener las posiciones enriquecidas con
   * taxSummary. El Dashboard las pasa a PositionsTableWithSlots.
   */
  onPositionsEnriched: (positions: AssetPosition[]) => void;
}

// ─── Componente ───────────────────────────────────────────────────────────────

export function TaxModuleBootstrap({
  plan,
  portfolioId,
  onPositionsEnriched,
}: TaxModuleBootstrapProps) {
  // ── 1. Registrar el slot de badge fiscal ───────────────────────────────────
  // order: 1 → se renderiza antes que el slot de pignoración (Módulo 4, order: 2)
  // Cast necesario: SlotComponent<object> es el tipo genérico del registro;
  // el Slot lo invoca con SlotRowProps en tiempo de ejecución.
  useRegisterSlot(
    "StatusBadgeSlot",
    TwoMonthWarningBadge as SlotComponent<object>,
    { order: 1 },
  );

  // ── 2. Estado del drawer ───────────────────────────────────────────────────
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [taxLoading, setTaxLoading] = useState(false);
  // Posiciones ya enriquecidas con taxSummary: el drawer debe leer de aquí,
  // no de plan.positions (que no lleva datos fiscales).
  const [enrichedLocal, setEnrichedLocal] = useState<AssetPosition[] | null>(null);

  // ── 3. Cargar datos fiscales cuando el plan cambia ─────────────────────────
  // Usamos una ref para evitar dobles llamadas en StrictMode de React
  const lastPlanRef = useRef<string | null>(null);

  const enrichPositions = useCallback(
    async (currentPlan: RebalancePlan) => {
      // Clave única del plan para evitar re-fetches con el mismo plan
      const planKey = `${portfolioId}-${currentPlan.asOf}`;
      if (lastPlanRef.current === planKey) return;
      lastPlanRef.current = planKey;

      setTaxLoading(true);
      try {
        // En modo mock, calculamos los datos fiscales localmente sin HTTP
        const taxMap = USE_MOCK
          ? buildMockTaxSummaries(currentPlan)
          : await fetchTaxSummariesForPlan(currentPlan, portfolioId);

        // Enriquecemos cada posición con su taxSummary correspondiente
        const enriched: AssetPosition[] = currentPlan.positions.map((pos) => {
          const summary = taxMap.get(pos.instrumentId);
          if (!summary || summary.error || !summary.frictionCost) return pos;
          return {
            ...pos,
            taxSummary: {
              taxableGainLoss: summary.frictionCost.taxableGainLoss,
              estimatedTaxCost: summary.frictionCost.estimatedTaxCost,
              affectedLots: summary.frictionCost.affectedLots,
              twoMonthRuleViolation: summary.frictionCost.twoMonthRuleViolation,
              warning: summary.frictionCost.warning,
            },
          };
        });

        setEnrichedLocal(enriched);
        onPositionsEnriched(enriched);
      } catch (err) {
        // El Módulo 2 no bloquea al Módulo 1, pero dejamos rastro del fallo
        console.error("[Módulo 2] No se pudieron cargar los datos fiscales:", err);
        setEnrichedLocal(null);
        onPositionsEnriched(currentPlan.positions as AssetPosition[]);
      } finally {
        setTaxLoading(false);
      }
    },
    [portfolioId, onPositionsEnriched],
  );

  useEffect(() => {
    if (plan) void enrichPositions(plan);
  }, [plan, enrichPositions]);

  // ── 4. Posiciones SELL con datos fiscales para el drawer ───────────────────
  const sellPositions: AssetPosition[] = (
    enrichedLocal ?? ((plan?.positions ?? []) as AssetPosition[])
  ).filter((p) => p.order?.side === "SELL");

  // ── 5. Render: botón de acceso al Drawer + el Drawer en sí ────────────────
  return (
    <>
      {/* Botón "Análisis Fiscal" — solo visible si hay órdenes de venta */}
      {plan && sellPositions.length > 0 && (
        <button
          onClick={() => setDrawerOpen(true)}
          className="group flex w-full items-center justify-between gap-3 rounded-xl border border-blue-100 bg-gradient-to-r from-blue-50/80 via-indigo-50/50 to-transparent p-3 text-left transition-all hover:border-blue-200 hover:shadow-sm dark:border-blue-900/40 dark:from-slate-900 dark:via-blue-950/30 dark:to-transparent dark:hover:border-blue-800"
          aria-label="Ver análisis fiscal del rebalanceo"
        >
          <span className="flex items-center gap-3">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-blue-200 bg-white/80 text-blue-600 dark:border-blue-800 dark:bg-blue-950/50 dark:text-blue-300">
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="16" y1="13" x2="8" y2="13" />
            <line x1="16" y1="17" x2="8" y2="17" />
          </svg>
          </span>
          <span>
            <span className="block text-xs font-semibold uppercase tracking-wider text-blue-900 dark:text-blue-200">Análisis Fiscal</span>
            <span className="mt-0.5 block text-xs text-slate-600 dark:text-slate-400">Revisa el impacto de las ventas antes de ejecutar</span>
          </span>
          </span>
          <span className="shrink-0 text-xs font-semibold text-blue-700 transition-transform group-hover:translate-x-0.5 dark:text-blue-300">Ver análisis →</span>
          {/* Dot de alerta si hay violaciones de la regla 2 meses */}
          {sellPositions.some(
            (p) =>
              (p as AssetPosition & { taxSummary?: { twoMonthRuleViolation?: boolean } })
                .taxSummary?.twoMonthRuleViolation ||
              (p as AssetPosition).flags?.taxWarning2Month,
          ) && (
            <span
              className="w-1.5 h-1.5 rounded-full bg-semantic-warning"
              aria-label="Hay advertencias fiscales"
            />
          )}
        </button>
      )}

      {/* Drawer de Tax Harvesting */}
      {drawerOpen && (
        <TaxHarvestingDrawer
          sellPositions={sellPositions}
          isLoading={taxLoading}
          onClose={() => setDrawerOpen(false)}
        />
      )}
    </>
  );
}
