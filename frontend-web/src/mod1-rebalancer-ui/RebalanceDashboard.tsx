"use client";

/**
 * RebalanceDashboard.tsx — Página principal del Módulo 1 (Balanceador Core)
 *
 * Consume el contrato de Raúl:
 *   GET /api/v1/portfolios/{portfolioId}/rebalance → RebalancePlan
 *   (port 8081, ver backend-core-java/src/main/resources/application.yml)
 *
 * Mientras el backend no esté activo, usa mockPortfolioService → custody_positions.json
 *
 * @module mod1-rebalancer-ui/RebalanceDashboard
 */

import React, { useCallback, useEffect, useState } from "react";
import type { RebalancePlan } from "@/core-ui/types/portfolio";
import { fetchRebalancePlan } from "@/core-ui/services/mockPortfolioService";
import { PositionsTableWithSlots } from "@/mod1-rebalancer-ui/PositionsTableWithSlots";

// ─── Hook: useRebalancePlan ───────────────────────────────────────────────────

interface RebalancePlanState {
  data: RebalancePlan | null;
  isLoading: boolean;
  error: string | null;
}

function useRebalancePlan(portfolioId: string) {
  const [state, setState] = useState<RebalancePlanState>({
    data: null,
    isLoading: true,
    error: null,
  });

  const load = useCallback(async () => {
    setState({ data: null, isLoading: true, error: null });
    try {
      // PARA CONECTAR AL BACKEND REAL: sustituir fetchRebalancePlan por:
      // import { portfolioApi } from "@/core-ui/api/apiClient";
      // const response = await portfolioApi.getRebalancePlan(portfolioId);
      const response = await fetchRebalancePlan(portfolioId);
      setState({ data: response, isLoading: false, error: null });
    } catch (err) {
      const message =
        err instanceof Error ? err.message : "Error desconocido al cargar el plan.";
      setState({ data: null, isLoading: false, error: message });
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

export function RebalanceDashboard({ portfolioId = "PF-001" }: RebalanceDashboardProps) {
  const { data, isLoading, error, refetch } = useRebalancePlan(portfolioId);

  return (
    <div className="flex flex-col gap-6 max-w-7xl mx-auto w-full">
      {/* Barra de acciones */}
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-white">Balanceador de Cartera</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Módulo 1 — Rebalanceo y gestión de posiciones
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1.5 text-xs bg-brand-950 border border-brand-800 text-brand-300 px-3 py-1.5 rounded-full">
            <span className="w-1.5 h-1.5 rounded-full bg-brand-400 animate-pulse" aria-hidden="true" />
            Datos simulados (mock)
          </span>
          <button
            onClick={() => void refetch()}
            disabled={isLoading}
            className={[
              "inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium",
              "bg-brand-600 text-white hover:bg-brand-500",
              "disabled:opacity-50 disabled:cursor-not-allowed",
              "transition-colors duration-150",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500",
            ].join(" ")}
          >
            <RefreshIcon spinning={isLoading} />
            {isLoading ? "Cargando..." : "Actualizar"}
          </button>
        </div>
      </div>

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
      <PositionsTableWithSlots
        plan={data}
        isLoading={isLoading}
        error={error}
      />
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
