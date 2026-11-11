"use client";

/**
 * RebalanceDashboard.tsx — Página principal del Módulo 1 (Balanceador Core)
 *
 * Consume el contrato de Raúl:
 *   GET /api/v1/portfolios/{portfolioId}/rebalance → RebalancePlan (schemaVersion 1.1)
 *   (backend en el puerto 8080, ver backend-core-java/src/main/resources/application.yml)
 *
 * RESPONSABILIDADES:
 *   1. Cargar el plan de rebalanceo del backend (portfolioService); con NEXT_PUBLIC_USE_MOCK=true usa el mock.
 *   2. «Rebalancear»: recalcula el plan, muestra las ÓRDENES y pide confirmación (OrderPreviewModal).
 *      Al confirmar, el backend registra los movimientos, que aparecen en «Transferencias».
 *   3. «Editar meta»: el cliente cambia sus pesos objetivo (TargetWeightsEditor) y se rebalancea con ellos.
 *   4. Alternar entre «Cartera Actual» y «Cartera Proyectada» (tras ejecutar las órdenes).
 *   5. Renderizar la PositionsTableWithSlots.
 *
 * En modo mock no hay escritura: se desactivan editar meta, confirmar y el selector de vista.
 *
 * NOTA SOBRE SLOTS: los "bootstrap" de los módulos futuros se montan aquí para registrar sus slots.
 *
 * @module mod1-rebalancer-ui/RebalanceDashboard
 */

import React, { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";
import type { RebalanceExecution } from "@/core-ui/types/transfers";
import type { Basket, ContributionExecution } from "@/core-ui/types/baskets";
import {
  executeRebalance,
  fetchBaskets,
  loadPortfolio,
  updateTargets,
  USE_MOCK,
} from "@/core-ui/services/portfolioService";
import { formatEur } from "@/core-ui/format";
import {
  toDisplayPlan,
  type PortfolioViewMode,
} from "@/core-ui/services/rebalancePlanAdapter";
import { AllocationCharts } from "@/mod1-rebalancer-ui/AllocationCharts";
import { PerformanceChart } from "@/mod1-rebalancer-ui/PerformanceChart";
import { BasketsPanel } from "@/mod1-rebalancer-ui/BasketsPanel";
import { CreateBasketModal } from "@/mod1-rebalancer-ui/CreateBasketModal";
import { ContributionModal } from "@/mod1-rebalancer-ui/ContributionModal";
import { OrderPreviewModal } from "@/mod1-rebalancer-ui/OrderPreviewModal";
import { PortfolioViewToggle } from "@/mod1-rebalancer-ui/PortfolioViewToggle";
import { PositionsTableWithSlots } from "@/mod1-rebalancer-ui/PositionsTableWithSlots";
import { RebalanceOrdersPanel } from "@/mod1-rebalancer-ui/RebalanceOrdersPanel";
import { TargetWeightsEditor } from "@/mod1-rebalancer-ui/TargetWeightsEditor";
import { TaxModuleBootstrap } from "@/mod2-tax-ui/TaxModuleBootstrap";
import type { AssetPosition } from "@/core-ui/types/portfolio";

// ─── Hook: usePortfolio ───────────────────────────────────────────────────────

interface PortfolioState {
  plan: RebalancePlan | null;
  isLoading: boolean;
  error: string | null;
}

function usePortfolio(portfolioId: string) {
  const [state, setState] = useState<PortfolioState>({
    plan: null,
    isLoading: true,
    error: null,
  });

  const load = useCallback(async () => {
    // Se conservan los datos previos mientras recarga (evita que los paneles parpadeen).
    setState((prev) => ({ ...prev, isLoading: true, error: null }));
    try {
      const plan = await loadPortfolio(portfolioId);
      setState({ plan, isLoading: false, error: null });
    } catch (err) {
      const message =
        err instanceof Error ? err.message : "Error desconocido al cargar el plan.";
      setState({ plan: null, isLoading: false, error: message });
    }
  }, [portfolioId]);

  useEffect(() => {
    void load();
  }, [load]);

  return { ...state, refetch: load };
}

// ─── Componente principal ─────────────────────────────────────────────────────

interface RebalanceDashboardProps {
  portfolioId?: string;
}

// Botones del sistema de diseño (ver .btn en globals.css): hover, foco visible y active consistentes.
const BUTTON_BASE = "btn";

export function RebalanceDashboard({ portfolioId = "PF-001" }: RebalanceDashboardProps) {
  const { plan, isLoading, error, refetch } = usePortfolio(portfolioId);

  // Módulo 2: posiciones enriquecidas con taxSummary por TaxModuleBootstrap.
  // Mientras no llegan, se usan las posiciones originales del plan.
  const [enrichedPositions, setEnrichedPositions] = useState<AssetPosition[] | null>(null);

  // Las órdenes solo se muestran tras pulsar «Rebalancear» (o guardar una meta nueva).
  const [showOrders, setShowOrders] = useState(false);
  const [editingTargets, setEditingTargets] = useState(false);
  // Vista de la tabla: cartera actual o cartera proyectada tras ejecutar las órdenes.
  const [viewMode, setViewMode] = useState<PortfolioViewMode>("current");
  // Confirmación de la ejecución (OrderPreviewModal) y resultado de la última ejecución.
  const [previewOpen, setPreviewOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [executeError, setExecuteError] = useState<string | null>(null);
  const [lastExecution, setLastExecution] = useState<RebalanceExecution | null>(null);
  // Agrupaciones (cestas) y aportación de efectivo («Invertir en Agrupación»)
  const [baskets, setBaskets] = useState<Basket[]>([]);
  const [investing, setInvesting] = useState<Basket | null>(null);
  const [creatingBasket, setCreatingBasket] = useState(false);
  const [lastContribution, setLastContribution] = useState<ContributionExecution | null>(null);

  // Las cestas no cambian al operar (solo su peso real), así que se cargan una vez. Sin backend (mock) no hay.
  useEffect(() => {
    if (USE_MOCK) return;
    let cancelled = false;
    fetchBaskets(portfolioId)
      .then((b) => !cancelled && setBaskets(b))
      .catch(() => !cancelled && setBaskets([])); // sin cestas se oculta el panel; el resto de la pantalla sigue
    return () => {
      cancelled = true;
    };
  }, [portfolioId]);

  // La vista se deriva del plan en cada render: alternar no vuelve a llamar al backend.
  // Si el Módulo 2 ha enriquecido las posiciones, se usan esas (con taxSummary).
  const displayPlan = useMemo(() => {
    if (!plan) return null;
    const base = toDisplayPlan(plan, viewMode);
    if (!enrichedPositions) return base;
    return { ...base, positions: enrichedPositions };
  }, [plan, viewMode, enrichedPositions]);

  async function handleRebalance() {
    if (USE_MOCK) {
      await refetch();
      return;
    }
    setShowOrders(true);
    setLastExecution(null);
    setViewMode("projected"); // al rebalancear se ve ya cómo quedan los pesos frente a la meta
    await refetch();
    setPreviewOpen(true); // pide confirmación (solo se pinta si el plan trae órdenes)
  }

  async function handleSaveTargets(weights: Record<string, number>) {
    await updateTargets(portfolioId, weights); // lanza si el backend rechaza la meta
    setEditingTargets(false);
    setShowOrders(true);
    setLastExecution(null);
    setViewMode("projected");
    await refetch();
  }

  /** Acepta las órdenes: el backend las registra como transferencias y actualiza la cartera. */
  async function handleConfirmExecution() {
    setSubmitting(true);
    setExecuteError(null);
    try {
      const execution = await executeRebalance(portfolioId);
      setLastExecution(execution);
      setPreviewOpen(false);
      setShowOrders(false); // ya no hay propuesta pendiente: la cartera está en su meta
      setViewMode("current");
      await refetch(); // pesos, valores y P&L reales tras ejecutar
    } catch (err) {
      setExecuteError(err instanceof Error ? err.message : "No se pudo ejecutar el rebalanceo.");
    } finally {
      setSubmitting(false);
    }
  }

  function closePreview() {
    setPreviewOpen(false);
    setExecuteError(null);
  }

  /** La aportación se ha ejecutado: se cierra el modal y se recarga la cartera con el dinero ya invertido. */
  async function handleContributionExecuted(execution: ContributionExecution) {
    setInvesting(null);
    setLastContribution(execution);
    setLastExecution(null);
    setShowOrders(false); // una propuesta de rebalanceo anterior ya no es válida: la cartera ha cambiado
    setViewMode("current");
    await refetch();
  }

  function handleCreateBasket(basket: Basket) {
    setBaskets((current) => [...current, basket]);
    setCreatingBasket(false);
  }

  return (
    <div className="flex flex-col gap-6 max-w-7xl mx-auto w-full">
      {/* Barra de acciones */}
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-fg">Mis Inversiones</h1>
          <p className="text-sm text-gray-500 mt-0.5">Rebalanceador de cartera — Módulo 1</p>
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          {/* Origen de datos: backend real (por defecto) o mock (NEXT_PUBLIC_USE_MOCK=true) */}
          <span className="inline-flex items-center gap-2 rounded-full border border-slate-200/80 bg-white/70 px-3 py-1.5 text-xs font-medium text-slate-600 shadow-sm dark:border-slate-800 dark:bg-slate-900/70 dark:text-slate-300">
          <span className="relative flex h-2 w-2" aria-hidden="true">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
          </span>
          {USE_MOCK ? "Datos simulados (mock)" : "Backend en vivo"}
          </span>
          {plan && !USE_MOCK && (
            <button
              type="button"
              onClick={() => setEditingTargets((v) => !v)}
              disabled={isLoading}
              className={`${BUTTON_BASE} btn-secondary border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800`}
            >
              Editar meta
            </button>
          )}
          <button
            type="button"
            onClick={() => void handleRebalance()}
            disabled={isLoading}
            className={`${BUTTON_BASE} btn-primary rounded-lg px-4 py-2 text-sm font-medium shadow-sm transition-all hover:bg-blue-700 active:scale-[0.98]`}
          >
            <RefreshIcon spinning={isLoading} />
            {isLoading ? "Cargando..." : USE_MOCK ? "Actualizar" : "Rebalancear"}
          </button>
        </div>
      </div>

      {/* Editor de la meta de balanceo (el cliente fija el % objetivo de cada activo) */}
      {plan && !USE_MOCK && editingTargets && (
        <TargetWeightsEditor
          positions={plan.positions}
          onSave={handleSaveTargets}
          onCancel={() => setEditingTargets(false)}
        />
      )}

      {/* Resultado de la última aportación a una agrupación */}
      {lastContribution && (
        <div
          className="flex items-center justify-between gap-3 flex-wrap rounded-xl border border-semantic-gain/40 bg-semantic-gain/10 px-5 py-3"
          role="status"
          data-testid="contribution-banner"
        >
          <p className="text-sm text-semantic-gain">
            Aportación de {formatEur(lastContribution.plan.amount)} invertida en {lastContribution.basketName}:{" "}
            {lastContribution.transfers.length} movimientos registrados (
            <span className="font-mono">{lastContribution.executionId}</span>).
          </p>
          <Link
            href="/transferencias"
            className="px-3 py-1.5 rounded-lg text-sm font-medium bg-semantic-gain/20 text-semantic-gain hover:bg-semantic-gain/30"
          >
            Ver transferencias
          </Link>
        </div>
      )}

      {/* Resultado de la última ejecución: enlaza con el historial de «Transferencias» */}
      {lastExecution && (
        <div
          className="flex items-center justify-between gap-3 flex-wrap rounded-xl border border-semantic-gain/40 bg-semantic-gain/10 px-5 py-3"
          role="status"
          data-testid="execution-banner"
        >
          <p className="text-sm text-semantic-gain">
            Rebalanceo ejecutado: {lastExecution.transfers.length} movimientos registrados (
            <span className="font-mono">{lastExecution.executionId}</span>). Pasarán de «En proceso» a
            «Completado» al liquidarse.
          </p>
          <Link
            href="/transferencias"
            className="px-3 py-1.5 rounded-lg text-sm font-medium bg-semantic-gain/20 text-semantic-gain hover:bg-semantic-gain/30"
          >
            Ver transferencias
          </Link>
        </div>
      )}

      {/* Órdenes de compra/venta resultantes del rebalanceo */}
      {plan && !USE_MOCK && showOrders && !error && <RebalanceOrdersPanel plan={plan} />}

      {/* Confirmación. Sin órdenes (ya está en su meta, p. ej. tras ejecutar) no se abre: evita un modal vacío. */}
      {plan && !USE_MOCK && previewOpen && !error && !isLoading && plan.orders.length > 0 && (
        <OrderPreviewModal
          plan={plan}
          submitting={submitting}
          error={executeError}
          onConfirm={() => void handleConfirmExecution()}
          onCancel={closePreview}
        />
      )}

      {/* Alterna entre la cartera actual y la proyectada tras ejecutar las órdenes */}
      {plan && !USE_MOCK && showOrders && !error && (
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <PortfolioViewToggle mode={viewMode} onChange={setViewMode} />
          <p className="text-sm text-gray-500" data-testid="view-caption">
            {viewMode === "projected"
              ? "Mostrando cómo quedará la cartera tras ejecutar las órdenes propuestas."
              : "Mostrando la cartera actual, sin ejecutar las órdenes."}
          </p>
        </div>
      )}

      {/*
        ── Módulo 2: Optimizador Fiscal ─────────────────────────────────────
        TaxModuleBootstrap registra el badge "StatusBadgeSlot", carga los datos
        fiscales del backend y expone el botón + drawer de Tax Harvesting.

        INSTRUCCIONES PARA COMPAÑERO 4 (Módulo 4 - Pledge):
          Añade aquí: <PledgeModuleBootstrap />
          que llame a useRegisterSlot("StatusBadgeSlot", PledgedLockIcon, { order: 2 })
                   y  useRegisterSlot("ActionRowSlot",   LtvHealthBar,    { order: 1 })
        ──────────────────────────────────────────────────────────────────── */}
      <TaxModuleBootstrap
        plan={plan}
        portfolioId={portfolioId}
        onPositionsEnriched={setEnrichedPositions}
      />

      {/* Tabla principal */}
      <PositionsTableWithSlots plan={displayPlan} isLoading={isLoading} error={error} />

      {/* Distribución de la cartera: sigue la vista de la tabla (actual o proyectada) */}
      {displayPlan && !isLoading && !error && (
        <PerformanceChart
          performance={
            displayPlan.performance ?? {
              totalValue: displayPlan.totalValue,
              totalCost: displayPlan.totalValue,
              unrealizedPnLAmount: 0,
              unrealizedPnLPercent: 0,
            }
          }
        />
      )}

      {displayPlan && !isLoading && !error && (
        <AllocationCharts
          positions={displayPlan.positions}
          viewLabel={viewMode === "projected" ? "Cartera proyectada" : "Cartera actual"}
        />
      )}

      {/* Agrupaciones de fondos: «Invertir en Agrupación» (aportación de efectivo) */}
      {plan && !USE_MOCK && !error && (
        <BasketsPanel
          baskets={baskets}
          nameOf={Object.fromEntries(plan.positions.map((p) => [p.instrumentId, p.name]))}
          onInvest={setInvesting}
          onCreate={() => setCreatingBasket(true)}
          disabled={isLoading}
        />
      )}

      {investing && (
        <ContributionModal
          portfolioId={portfolioId}
          basket={investing}
          onClose={() => setInvesting(null)}
          onExecuted={(execution) => void handleContributionExecuted(execution)}
        />
      )}

      {creatingBasket && plan && (
        <CreateBasketModal
          positions={plan.positions}
          onClose={() => setCreatingBasket(false)}
          onCreate={handleCreateBasket}
        />
      )}
    </div>
  );
}

function RefreshIcon({ spinning }: { spinning: boolean }) {
  return (
    <svg
      width="14" height="14" viewBox="0 0 24 24"
      fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round"
      className={spinning ? "animate-spin" : ""}
      aria-hidden="true"
    >
      <polyline points="23 4 23 10 17 10" />
      <polyline points="1 20 1 14 7 14" />
      <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
    </svg>
  );
}
