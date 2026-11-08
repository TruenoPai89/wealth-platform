"use client";

/**
 * LtvHealthBar.tsx — Módulo 4: Gestor de Pignoración / Riesgo
 *
 * Barra de salud del LTV (Loan-To-Value) que se inyectará en "ActionRowSlot"
 * para posiciones pignoradas, mostrando visualmente el nivel de riesgo.
 *
 * ZONAS:
 *   Verde  (0% – LtvWarning):       LTV saludable
 *   Ámbar  (LtvWarning – LtvLiquid): Warning: aproximándose al límite
 *   Rojo   (LtvLiquid – 100%):      Riesgo de llamada de margen / liquidación
 *
 * ESTADO ACTUAL: Implementación completa. Pendiente de registrar (Fase 4).
 *
 * @module mod4-pledge-ui/LtvHealthBar
 */

import React from "react";
import type { SlotRowProps } from "@/core-ui/SlotRegistry";

export function LtvHealthBar({ position }: SlotRowProps) {
  if (!position.flags?.isPledged || !position.pledgeInfo) return null;

  const { ltvRatio, ltvThresholdWarning, ltvThresholdLiquidation, pledgeContractId, loanId } =
    position.pledgeInfo;

  const ltvPct = ltvRatio * 100;
  const warningPct = ltvThresholdWarning * 100;
  const liquidationPct = ltvThresholdLiquidation * 100;

  const isLiquidationRisk = ltvRatio >= ltvThresholdLiquidation;
  const isWarning = ltvRatio >= ltvThresholdWarning;

  const barColorClass = isLiquidationRisk
    ? "bg-semantic-loss"
    : isWarning
      ? "bg-semantic-warning"
      : "bg-semantic-gain";

  const statusText = isLiquidationRisk
    ? "⚠ Riesgo de liquidación"
    : isWarning
      ? "⚡ En zona de warning"
      : "✓ LTV saludable";

  const statusColorClass = isLiquidationRisk
    ? "text-semantic-loss"
    : isWarning
      ? "text-semantic-warning"
      : "text-semantic-gain";

  return (
    <div
      className="flex flex-col gap-1.5 py-2 px-1"
      aria-label={`Salud LTV de la posición pignorada ${position.ticker}`}
    >
      {/* Info línea */}
      <div className="flex items-center justify-between gap-4 text-xs">
        <div className="flex items-center gap-3">
          <span className="text-gray-500">
            Contrato{" "}
            <span className="font-mono text-gray-400">{pledgeContractId}</span>
          </span>
          <span className="text-gray-600">·</span>
          <span className="text-gray-500">
            Préstamo{" "}
            <span className="font-mono text-gray-400">{loanId}</span>
          </span>
        </div>
        <span className={`font-semibold ${statusColorClass}`}>
          LTV {ltvPct.toFixed(1)}% — {statusText}
        </span>
      </div>

      {/* Barra de LTV */}
      <div className="relative h-2 bg-surface-overlay rounded-full overflow-hidden w-full max-w-sm">
        {/* Umbral warning */}
        <div
          className="absolute top-0 bottom-0 w-0.5 bg-semantic-warning/60 z-10"
          style={{ left: `${warningPct}%` }}
          title={`Warning: ${warningPct}%`}
        />
        {/* Umbral liquidación */}
        <div
          className="absolute top-0 bottom-0 w-0.5 bg-semantic-loss/60 z-10"
          style={{ left: `${liquidationPct}%` }}
          title={`Liquidación: ${liquidationPct}%`}
        />
        {/* Barra de LTV actual */}
        <div
          className={`h-full rounded-full transition-all duration-300 ${barColorClass}`}
          style={{ width: `${Math.min(ltvPct, 100)}%` }}
        />
      </div>

      {/* Leyenda */}
      <div className="flex items-center gap-4 text-xs text-gray-600">
        <span>0%</span>
        <span className="text-semantic-warning/70">Warning: {warningPct.toFixed(0)}%</span>
        <span className="text-semantic-loss/70">Liquidación: {liquidationPct.toFixed(0)}%</span>
        <span className="ml-auto">100%</span>
      </div>
    </div>
  );
}
