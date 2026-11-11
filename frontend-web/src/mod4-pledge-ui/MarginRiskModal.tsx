"use client";

/**
 * MarginRiskModal.tsx — Informe de riesgo de margin call.
 *
 * Las cifras (nivel de seguridad, probabilidades 30/90/365 días, test de estrés -10/-20/-30 %, recomendaciones)
 * vienen del backend Java, que las calcula de forma determinista. La explicación en lenguaje natural la redacta
 * el servicio Python con Claude y se verifica contra esas cifras; si ese servicio no está disponible, el informe
 * se muestra igual sin ella.
 *
 * @module mod4-pledge-ui/MarginRiskModal
 */

import React, { useEffect, useRef, useState } from "react";
import { formatEur } from "@/core-ui/format";
import { explainMargin } from "@/core-ui/services/marginService";
import type { MarginRiskAnalysis, SecurityLevel } from "@/core-ui/types/margin";
import type { Explanation } from "@/core-ui/types/pledge";

const pct = (x: number, d = 1) => `${(x * 100).toFixed(d).replace(".", ",")} %`;

const LEVEL: Record<SecurityLevel, { label: string; text: string; ring: string; blurb: string }> = {
  HIGH: {
    label: "Alto",
    text: "text-semantic-gain",
    ring: "border-semantic-gain/50 bg-semantic-gain/10",
    blurb: "La garantía aguanta caídas fuertes sin margin call.",
  },
  MEDIUM: {
    label: "Medio",
    text: "text-semantic-warning",
    ring: "border-semantic-warning/50 bg-semantic-warning/10",
    blurb: "Aguanta caídas moderadas, pero una caída fuerte provocaría margin call.",
  },
  LOW: {
    label: "Bajo",
    text: "text-semantic-loss",
    ring: "border-semantic-loss/50 bg-semantic-loss/10",
    blurb: "Hay margin call o una caída pequeña lo provocaría.",
  },
};

interface Props {
  analysis: MarginRiskAnalysis;
  onClose: () => void;
}

export function MarginRiskModal({ analysis, onClose }: Props) {
  const [explanation, setExplanation] = useState<Explanation | null>(null);
  const [explaining, setExplaining] = useState(true);
  const closeRef = useRef<HTMLButtonElement>(null);
  const level = LEVEL[analysis.securityLevel];

  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // La explicación se pide una vez por análisis; si el usuario cierra antes de que llegue, se descarta.
  useEffect(() => {
    let cancelled = false;
    setExplaining(true);
    setExplanation(null);
    void explainMargin(analysis).then((e) => {
      if (cancelled) return;
      setExplanation(e);
      setExplaining(false);
    });
    return () => {
      cancelled = true;
    };
  }, [analysis]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="margin-risk-title"
      data-testid="margin-risk-modal"
    >
      <div className="flex max-h-[90vh] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-surface-border bg-surface-raised shadow-2xl">
        <header className="flex items-start justify-between gap-4 border-b border-surface-border px-6 py-4">
          <div>
            <h2 id="margin-risk-title" className="text-lg font-semibold text-fg">
              Análisis de riesgo de Margin Call
            </h2>
            <p className="text-xs text-gray-500">
              Cartera {analysis.portfolioId} · crédito {formatEur(analysis.metrics.loanAmount)} · LTV{" "}
              {pct(analysis.metrics.ltv)} (umbral {pct(analysis.metrics.marginCallLtv, 0)})
            </p>
          </div>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label="Cerrar"
            className="rounded-md px-2 py-1 text-gray-400 hover:bg-surface-overlay hover:text-fg"
          >
            ✕
          </button>
        </header>

        <div className="flex flex-col gap-6 overflow-y-auto px-6 py-5">
          {/* Nivel de seguridad */}
          <div className={`rounded-lg border px-4 py-3 ${level.ring}`} data-testid="security-level">
            <p className="text-xs text-gray-400">Nivel de seguridad</p>
            <p className={`text-2xl font-bold ${level.text}`}>{level.label}</p>
            <p className="text-sm text-gray-300">{level.blurb}</p>
          </div>

          {/* Explicación IA */}
          <div className="rounded-lg bg-surface-overlay p-4 text-sm text-gray-200" data-testid="ai-explanation">
            {explaining ? (
              <p className="animate-pulse text-gray-400">Redactando la explicación…</p>
            ) : explanation ? (
              <>
                <p>{explanation.texto}</p>
                <p className="mt-2 text-xs text-gray-500">
                  {explanation.fuente === "claude"
                    ? "Explicación generada por Claude · cifras verificadas contra el motor"
                    : "Explicación automática (sin IA)"}
                </p>
              </>
            ) : (
              <p className="text-gray-400">
                No se pudo contactar con el servicio de IA (puerto 8000). El informe de abajo es completo y sale del
                motor de cálculo.
              </p>
            )}
          </div>

          {/* Probabilidad por horizonte */}
          <section aria-labelledby="mc-prob-title">
            <h3 id="mc-prob-title" className="text-sm font-semibold text-fg">
              Probabilidad de margin call
            </h3>
            <div className="mt-2 grid grid-cols-3 gap-3">
              {analysis.marginCallProbability.map((p) => (
                <div key={p.horizonDays} className="rounded-lg bg-surface-overlay p-3 text-center" data-testid={`prob-${p.horizonDays}`}>
                  <p className="text-xs text-gray-400">{p.horizonDays} días</p>
                  <p className="text-xl font-semibold text-fg">{pct(p.probability, 2)}</p>
                </div>
              ))}
            </div>
          </section>

          {/* Test de estrés */}
          <section aria-labelledby="mc-stress-title">
            <h3 id="mc-stress-title" className="text-sm font-semibold text-fg">
              Test de estrés (caída simultánea de todos los fondos)
            </h3>
            <div className="mt-2 overflow-x-auto">
              <table className="w-full text-sm" aria-label="Escenarios de estrés">
                <thead>
                  <tr className="text-left text-xs text-gray-500">
                    <th className="py-1 pr-3 font-normal">Escenario</th>
                    <th className="py-1 pr-3 text-right font-normal">Garantía</th>
                    <th className="py-1 pr-3 text-right font-normal">LTV</th>
                    <th className="py-1 pr-3 text-left font-normal">Resultado</th>
                    <th className="py-1 text-right font-normal">Para salir</th>
                  </tr>
                </thead>
                <tbody>
                  {analysis.stressTest.map((s) => {
                    const call = s.zone === "MARGIN_CALL";
                    return (
                      <tr key={s.drop} className="border-t border-surface-border/60" data-testid={`stress-${Math.round(s.drop * 100)}`}>
                        <td className="py-2 pr-3 font-medium text-fg">−{(s.drop * 100).toFixed(0)} %</td>
                        <td className="py-2 pr-3 text-right font-mono">{formatEur(s.collateralValue)}</td>
                        <td className={`py-2 pr-3 text-right font-mono ${call ? "text-semantic-loss" : "text-fg"}`}>{pct(s.ltv)}</td>
                        <td className={`py-2 pr-3 ${call ? "text-semantic-loss" : "text-semantic-gain"}`}>
                          {call ? "Margin call" : "Sin margin call"}
                        </td>
                        <td className="py-2 text-right font-mono text-xs text-gray-400">
                          {call ? `+${formatEur(s.topUpNeeded)} o amortizar ${formatEur(s.repayNeeded)}` : "—"}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </section>

          {/* Recomendaciones */}
          <section aria-labelledby="mc-rec-title">
            <h3 id="mc-rec-title" className="text-sm font-semibold text-fg">
              Recomendaciones de rebalanceo de la garantía
            </h3>
            <ul className="mt-2 flex flex-col gap-2" data-testid="recommendations">
              {analysis.recommendations.map((r) => (
                <li key={r.code} className="rounded-lg bg-surface-overlay px-3 py-2 text-sm text-gray-200" data-testid={`rec-${r.code}`}>
                  {r.message}
                </li>
              ))}
            </ul>
          </section>

          {/* Supuestos */}
          <details className="text-xs text-gray-500">
            <summary className="cursor-pointer text-gray-400">Supuestos del cálculo</summary>
            <ul className="mt-2 list-disc space-y-1 pl-5">
              {analysis.assumptions.map((a) => (
                <li key={a}>{a}</li>
              ))}
            </ul>
          </details>

          <p className="text-xs text-gray-500">Información orientativa; no constituye asesoramiento financiero.</p>
        </div>
      </div>
    </div>
  );
}
