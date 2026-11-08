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
import {
  executeRebalance,
  loadPortfolio,
  updateTargets,
  USE_MOCK,
} from "@/core-ui/services/portfolioService";
import {
  toDisplayPlan,
  type PortfolioViewMode,
} from "@/core-ui/services/rebalancePlanAdapter";
import { OrderPreviewModal } from "@/mod1-rebalancer-ui/OrderPreviewModal";
import { PortfolioViewToggle } from "@/mod1-rebalancer-ui/PortfolioViewToggle";
import { PositionsTableWithSlots } from "@/mod1-rebalancer-ui/PositionsTableWithSlots";
import { RebalanceOrdersPanel } from "@/mod1-rebalancer-ui/RebalanceOrdersPanel";
import { TargetWeightsEditor } from "@/mod1-rebalancer-ui/TargetWeightsEditor";

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

const BUTTON_BASE = [
  "inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium",
  "disabled:opacity-50 disabled:cursor-not-allowed",
  "transition-colors duration-150",
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500",
].join(" ");

export function RebalanceDashboard({ portfolioId = "PF-001" }: RebalanceDashboardProps) {
  const { plan, isLoading, error, refetch } = usePortfolio(portfolioId);

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

  // La vista se deriva del plan en cada render: alternar no vuelve a llamar al backend.
  const displayPlan = useMemo(
    () => (plan ? toDisplayPlan(plan, viewMode) : null),
    [plan, viewMode],
  );

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

  return (
    <div className="flex flex-col gap-6 max-w-7xl mx-auto w-full">
      {/* Barra de acciones */}
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-white">Mis Inversiones</h1>
          <p className="text-sm text-gray-500 mt-0.5">Rebalanceador de cartera — Módulo 1</p>
        </div>
        <div className="flex items-center gap-2">
          {/* Origen de datos: backend real (por defecto) o mock (NEXT_PUBLIC_USE_MOCK=true) */}
          <span className="inline-flex items-center gap-1.5 text-xs bg-brand-950 border border-brand-800 text-brand-300 px-3 py-1.5 rounded-full">
            <span className="w-1.5 h-1.5 rounded-full bg-brand-400 animate-pulse" aria-hidden="true" />
            {USE_MOCK ? "Datos simulados (mock)" : "Backend en vivo"}
          </span>
          {plan && !USE_MOCK && (
            <button
              type="button"
              onClick={() => setEditingTargets((v) => !v)}
              disabled={isLoading}
              className={`${BUTTON_BASE} bg-surface-overlay text-gray-200 hover:text-white border border-surface-border`}
            >
              Editar meta
            </button>
          )}
          <button
            type="button"
            onClick={() => void handleRebalance()}
            disabled={isLoading}
            className={`${BUTTON_BASE} bg-brand-600 text-white hover:bg-brand-500`}
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
        ── Punto de extensión para módulos futuros ───────────────────────────
        INSTRUCCIONES PARA COMPAÑERO 3 (Módulo 2 - Tax):
          Añade aquí: <TaxModuleBootstrap />
          que llame a useRegisterSlot("StatusBadgeSlot", TwoMonthWarningBadge)

        INSTRUCCIONES PARA COMPAÑERO 4 (Módulo 4 - Pledge):
          Añade aquí: <PledgeModuleBootstrap />
          que llame a useRegisterSlot("StatusBadgeSlot", PledgedLockIcon, { order: 2 })
                   y  useRegisterSlot("ActionRowSlot",   LtvHealthBar,    { order: 1 })
        ──────────────────────────────────────────────────────────────────── */}

      {/* Tabla principal */}
      <PositionsTableWithSlots plan={displayPlan} isLoading={isLoading} error={error} />
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
