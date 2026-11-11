"use client";

/**
 * MarginSimulator.tsx — Pignoración configurable a mano + análisis de margin call en tiempo real.
 *
 * Carga la cartera del simulador (PF-001), deja configurar fondos en garantía y crédito, y recalcula el análisis
 * en el backend (POST /api/v1/margin/analyze) 250 ms después del último cambio. Las respuestas que llegan fuera
 * de orden se descartan. Convive con PledgePanel (flujo por contrato) sin compartir estado ni endpoints.
 *
 * @module mod4-pledge-ui/MarginSimulator
 */

import React, { useEffect, useMemo, useRef, useState } from "react";
import { analyzeMargin } from "@/core-ui/services/marginService";
import { loadPortfolio } from "@/core-ui/services/portfolioService";
import type { MarginRiskAnalysis } from "@/core-ui/types/margin";
import { MarginDashboard } from "@/mod4-pledge-ui/MarginDashboard";
import { MarginRiskModal } from "@/mod4-pledge-ui/MarginRiskModal";
import { PledgeConfigPanel } from "@/mod4-pledge-ui/PledgeConfigPanel";
import {
  DEFAULT_PORTFOLIO_ID,
  defaultConfig,
  fundsFromPlan,
  toRequest,
  type FundOption,
  type PledgeConfigState,
} from "@/mod4-pledge-ui/pledgeConfig";

const DEBOUNCE_MS = 250;

interface Props {
  portfolioId?: string;
}

export function MarginSimulator({ portfolioId = DEFAULT_PORTFOLIO_ID }: Props) {
  const [funds, setFunds] = useState<FundOption[]>([]);
  const [config, setConfig] = useState<PledgeConfigState | null>(null);
  const [analysis, setAnalysis] = useState<MarginRiskAnalysis | null>(null);
  const [calculating, setCalculating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showReport, setShowReport] = useState(false);
  const requestId = useRef(0);

  // 1. Cartera del simulador
  useEffect(() => {
    let cancelled = false;
    loadPortfolio(portfolioId)
      .then((plan) => {
        if (cancelled) return;
        const options = fundsFromPlan(plan);
        setFunds(options);
        setConfig(defaultConfig(options));
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "No se pudo cargar la cartera.");
      });
    return () => {
      cancelled = true;
    };
  }, [portfolioId]);

  // 2. Recalcular al cambiar la configuración (con debounce)
  const request = useMemo(() => (config ? toRequest(config, portfolioId) : null), [config, portfolioId]);

  useEffect(() => {
    if (!config) return;
    if (!request) {
      setAnalysis(null);
      setError(null);
      return;
    }
    const id = ++requestId.current;
    setCalculating(true);
    const timer = setTimeout(() => {
      analyzeMargin(request)
        .then((result) => {
          if (id !== requestId.current) return;
          setAnalysis(result);
          setError(null);
        })
        .catch((e) => {
          if (id !== requestId.current) return;
          // Un 400 de validación no debe dejar en pantalla cifras de otra configuración.
          setAnalysis(null);
          setError(e instanceof Error ? e.message : "No se pudo calcular el análisis.");
        })
        .finally(() => {
          if (id === requestId.current) setCalculating(false);
        });
    }, DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [request, config]);

  if (!config) {
    return (
      <p className="text-sm text-gray-400" role="status">
        {error ?? "Cargando la cartera del simulador…"}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-6" data-testid="margin-simulator">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-semibold text-fg">Simulador de pignoración y margin call</h2>
          <p className="text-sm text-gray-400">
            Sin contrato: define la garantía y el crédito y ve el riesgo al momento (cartera {portfolioId}).
          </p>
        </div>

        <button
          type="button"
          onClick={() => setShowReport(true)}
          disabled={!analysis || calculating}
          className="rounded-lg bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white shadow hover:bg-brand-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-300 disabled:cursor-not-allowed disabled:opacity-50"
          data-testid="analyze-risk-button"
        >
          ✦ Analizar Riesgo de Margin Call con IA
        </button>
      </div>

      <PledgeConfigPanel funds={funds} config={config} onChange={setConfig} />

      {error && (
        <p className="rounded-lg border border-semantic-loss/40 bg-semantic-loss/10 px-4 py-3 text-sm text-semantic-loss" role="alert" data-testid="margin-error">
          {error}
        </p>
      )}

      {!request && !error && (
        <p className="text-sm text-gray-400" role="status">
          Selecciona al menos un fondo en garantía para ver el análisis.
        </p>
      )}

      {analysis && <MarginDashboard analysis={analysis} stale={calculating} />}

      {showReport && analysis && <MarginRiskModal analysis={analysis} onClose={() => setShowReport(false)} />}
    </div>
  );
}
