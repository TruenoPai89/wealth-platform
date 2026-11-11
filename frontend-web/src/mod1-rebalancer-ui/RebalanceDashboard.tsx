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
import { BasketsPanel } from "@/mod1-rebalancer-ui/BasketsPanel";
import { ContributionModal } from "@/mod1-rebalancer-ui/ContributionModal";
import { OrderPreviewModal } from "@/mod1-rebalancer-ui/OrderPreviewModal";
import { PortfolioViewToggle } from "@/mod1-rebalancer-ui/PortfolioViewToggle";
import { PositionsTableWithSlots } from "@/mod1-rebalancer-ui/PositionsTableWithSlots";
import { RebalanceOrdersPanel } from "@/mod1-rebalancer-ui/RebalanceOrdersPanel";
import { TargetWeightsEditor } from "@/mod1-rebalancer-ui/TargetWeightsEditor";
import { TaxModuleBootstrap } from "@/mod2-tax-ui/TaxModuleBootstrap";
import { VoiceAgentModal } from "@/mod5-voice-ui/VoiceAgentModal";
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

const BUTTON_BASE = [
  "inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium",
  "disabled:opacity-50 disabled:cursor-not-allowed",
  "transition-colors duration-150",
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500",
].join(" ");

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
  const [lastContribution, setLastContribution] = useState<ContributionExecution | null>(null);
  // Llamada de voz con el asesor de IA (módulo 5)
  const [voiceOpen, setVoiceOpen] = useState(false);

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
  // El Módulo 2 solo aporta el taxSummary de las posiciones con orden de VENTA: se superpone sobre las posiciones
  // de la vista activa (actual o proyectada). Nunca se sustituyen las posiciones, o la vista proyectada
  // (tabla y gráficos) quedaría anclada a la cartera actual.
  const displayPlan = useMemo(() => {
    if (!plan) return null;
    const base = toDisplayPlan(plan, viewMode);
    if (!enrichedPositions || viewMode === "projected") return base;
    const taxById = new Map(enrichedPositions.map((p) => [p.instrumentId, p.taxSummary]));
    return {
      ...base,
      positions: base.positions.map((p) => {
        const taxSummary = p.order?.side === "SELL" ? taxById.get(p.instrumentId) : undefined;
        return taxSummary ? { ...p, taxSummary } : p;
      }),
    };
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

  return (
    <div className="flex flex-col gap-6 max-w-7xl mx-auto w-full">
      {/* Barra de acciones */}
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-fg">Mis Inversiones</h1>
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
              className={`${BUTTON_BASE} bg-surface-overlay text-gray-200 hover:text-fg border border-surface-border`}
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

      {/* Llamada de voz: el asesor de IA llama al cliente y ya conoce su cartera. Requiere backend (no en mock). */}
      {!USE_MOCK && (
        <button
          type="button"
          onClick={() => setVoiceOpen(true)}
          data-testid="call-advisor"
          className="group relative flex w-full items-center gap-4 overflow-hidden rounded-xl border border-brand-500/40 bg-gradient-to-r from-brand-600/20 via-brand-600/10 to-transparent px-5 py-4 text-left transition-colors hover:border-brand-400/70 hover:from-brand-600/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
        >
          <span className="relative flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-brand-600 text-white shadow-lg shadow-brand-600/30">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z" />
            </svg>
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-base font-semibold text-fg">Llamar a mi Asesor de IA</span>
            <span className="block text-sm text-gray-500">
              Conversa por voz sobre tu cartera, tu liquidez y tus agrupaciones. Te llamamos al momento.
            </span>
          </span>
          <span className="hidden shrink-0 rounded-lg bg-brand-600 px-3 py-2 text-sm font-medium text-white transition-colors group-hover:bg-brand-500 sm:block">
            Llamar ahora
          </span>
        </button>
      )}

      {voiceOpen && (
        <VoiceAgentModal userId="CLI-001" clientName="Familia Mendoza Ruiz" onClose={() => setVoiceOpen(false)} />
      )}

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
        <AllocationCharts
          positions={displayPlan.positions}
          viewLabel={viewMode === "projected" ? "Cartera proyectada" : "Cartera actual"}
        />
      )}

      {/* Agrupaciones de fondos: «Invertir en Agrupación» (aportación de efectivo) */}
      {plan && !USE_MOCK && !error && baskets.length > 0 && (
        <BasketsPanel
          baskets={baskets}
          nameOf={Object.fromEntries(plan.positions.map((p) => [p.instrumentId, p.name]))}
          onInvest={setInvesting}
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
