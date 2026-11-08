"use client";

/**
 * RebalanceDashboard.tsx — Página principal del Módulo 1 (Balanceador Core)
 *
 * RESPONSABILIDADES:
 *   1. Orquestar la carga de datos desde mockPortfolioService.
 *   2. Gestionar el estado de carga / error con hooks de React.
 *   3. Renderizar la PositionsTableWithSlots con los datos cargados.
 *   4. En el futuro: aquí se integrarán TargetWeightsEditor y OrderPreviewModal.
 *
 * NOTA SOBRE SLOTS:
 *   Este componente es también el lugar correcto donde los "bootstrap" de los
 *   módulos futuros deberían montarse para registrar sus slots.
 *   Cuando el Módulo 2 esté listo, el Compañero 3 añadirá aquí:
 *     <TaxModuleBootstrap />  ← registra TwoMonthWarningBadge en StatusBadgeSlot
 *
 * @module mod1-rebalancer-ui/RebalanceDashboard
 */

import React, { useCallback, useEffect, useState } from "react";
import type { CustodyPositionsResponse } from "@/core-ui/types/portfolio";
import { fetchCustodyPositions } from "@/core-ui/services/mockPortfolioService";
import { PositionsTableWithSlots } from "@/mod1-rebalancer-ui/PositionsTableWithSlots";

// ─── Estado interno del hook de datos ────────────────────────────────────────

interface PortfolioDataState {
  data: CustodyPositionsResponse | null;
  isLoading: boolean;
  error: string | null;
}

// ─── Hook: useCustodyPositions ────────────────────────────────────────────────

/**
 * Hook que encapsula la carga de posiciones desde el mock service.
 * Cuando el backend esté listo, solo cambiará la implementación interna
 * de fetchCustodyPositions — este hook no necesitará modificarse.
 */
function useCustodyPositions(portfolioId: string) {
  const [state, setState] = useState<PortfolioDataState>({
    data: null,
    isLoading: true,
    error: null,
  });

  const load = useCallback(async () => {
    setState({ data: null, isLoading: true, error: null });
    try {
      const response = await fetchCustodyPositions(portfolioId);
      setState({ data: response, isLoading: false, error: null });
    } catch (err) {
      const message =
        err instanceof Error
          ? err.message
          : "Error desconocido al cargar las posiciones.";
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
  /** ID del portfolio a cargar. En Fase 1 usamos el ID del mock. */
  portfolioId?: string;
}

export function RebalanceDashboard({
  portfolioId = "PTF-00142",
}: RebalanceDashboardProps) {
  const { data, isLoading, error, refetch } = useCustodyPositions(portfolioId);

  return (
    <div className="flex flex-col gap-6 max-w-7xl mx-auto w-full">
      {/* Barra de acciones del módulo */}
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-white">Balanceador de Cartera</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Módulo 1 — Rebalanceo y gestión de posiciones
          </p>
        </div>
        <div className="flex items-center gap-2">
          {/* Indicador de datos simulados */}
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
            {isLoading ? (
              <>
                <RefreshIcon spinning />
                Cargando...
              </>
            ) : (
              <>
                <RefreshIcon spinning={false} />
                Actualizar
              </>
            )}
          </button>
        </div>
      </div>

      {/*
        ── Punto de extensión para módulos futuros ──────────────────────────
        INSTRUCCIONES PARA COMPAÑERO 3 (Módulo 2 - Tax):
          Añade aquí un componente <TaxModuleBootstrap /> que llame internamente
          a useRegisterSlot("StatusBadgeSlot", TwoMonthWarningBadge).
          NO modifiques PositionsTableWithSlots.tsx.

        INSTRUCCIONES PARA COMPAÑERO 4 (Módulo 4 - Pledge):
          Añade aquí un componente <PledgeModuleBootstrap /> que registre
          useRegisterSlot("StatusBadgeSlot", PledgedLockIcon, { order: 2 }).
          El order: 2 garantiza que el badge de pignoración aparece después del fiscal.
        ─────────────────────────────────────────────────────────────────── */}

      {/* Tabla principal de posiciones */}
      {data ? (
        <PositionsTableWithSlots
          meta={data.meta}
          positions={data.positions}
          isLoading={isLoading}
          error={error}
        />
      ) : isLoading ? (
        // Mientras se carga por primera vez, pasamos un meta vacío y activamos isLoading
        <PositionsTableWithSlots
          meta={{
            portfolioId: "",
            clientId: "",
            clientName: "",
            baseCurrency: "EUR",
            valuationDate: new Date().toISOString().split("T")[0] ?? "",
            totalMarketValue: 0,
            totalCostBasis: 0,
            totalUnrealizedPnl: 0,
            totalUnrealizedPnlPct: 0,
          }}
          positions={[]}
          isLoading={true}
          error={null}
        />
      ) : (
        <PositionsTableWithSlots
          meta={{
            portfolioId: "",
            clientId: "",
            clientName: "",
            baseCurrency: "EUR",
            valuationDate: new Date().toISOString().split("T")[0] ?? "",
            totalMarketValue: 0,
            totalCostBasis: 0,
            totalUnrealizedPnl: 0,
            totalUnrealizedPnlPct: 0,
          }}
          positions={[]}
          isLoading={false}
          error={error}
        />
      )}
    </div>
  );
}

// ─── Icono de refresco inline ─────────────────────────────────────────────────

function RefreshIcon({ spinning }: { spinning: boolean }) {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={spinning ? "animate-spin" : ""}
      aria-hidden="true"
    >
      <polyline points="23 4 23 10 17 10" />
      <polyline points="1 20 1 14 7 14" />
      <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
    </svg>
  );
}
