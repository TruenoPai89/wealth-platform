"use client";

/**
 * PledgedLockIcon.tsx — Módulo 4: Gestor de Pignoración / Riesgo
 *
 * Badge con icono de candado que se inyecta en "StatusBadgeSlot" de la tabla.
 * Aparece en las filas donde position.flags.isPledged === true.
 *
 * CÓMO SE CONECTA A LA TABLA (Patrón de Slots):
 *   useRegisterSlot("StatusBadgeSlot", PledgedLockIcon, { order: 2 });
 *   El order: 2 garantiza que aparece DESPUÉS del TwoMonthWarningBadge (order: 1).
 *
 * ESTADO ACTUAL: Implementación completa. Pendiente de registrar (Fase 4).
 *
 * @module mod4-pledge-ui/PledgedLockIcon
 */

import React from "react";
import type { SlotRowProps } from "@/core-ui/SlotRegistry";

export function PledgedLockIcon({ position }: SlotRowProps) {
  if (!position.flags.isPledged || !position.pledgeInfo) return null;

  const { ltvRatio, ltvThresholdWarning, ltvThresholdLiquidation } = position.pledgeInfo;

  const isLiquidationRisk = ltvRatio >= ltvThresholdLiquidation;
  const isWarning = ltvRatio >= ltvThresholdWarning;

  const colorClass = isLiquidationRisk
    ? "bg-semantic-loss/15 text-semantic-loss border-semantic-loss/30"
    : isWarning
      ? "bg-semantic-warning/15 text-semantic-warning border-semantic-warning/30"
      : "bg-semantic-pledged/15 text-semantic-pledged border-semantic-pledged/30";

  const tooltipText = isLiquidationRisk
    ? `¡RIESGO DE LIQUIDACIÓN! LTV: ${(ltvRatio * 100).toFixed(1)}% (umbral: ${(ltvThresholdLiquidation * 100).toFixed(0)}%)`
    : isWarning
      ? `Atención: LTV en zona de warning: ${(ltvRatio * 100).toFixed(1)}% (umbral: ${(ltvThresholdWarning * 100).toFixed(0)}%)`
      : `Pignorado como garantía · LTV: ${(ltvRatio * 100).toFixed(1)}% · Préstamo ${position.pledgeInfo.loanId}`;

  return (
    <span
      className={[
        "inline-flex items-center gap-1 px-2 py-0.5 rounded",
        "text-xs font-semibold border",
        colorClass,
      ].join(" ")}
      title={tooltipText}
      aria-label={`Posición pignorada. ${tooltipText}`}
    >
      {/* Icono de candado */}
      <svg
        width="10"
        height="10"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
        <path d="M7 11V7a5 5 0 0 1 10 0v4" />
      </svg>
      LTV {(ltvRatio * 100).toFixed(0)}%
    </span>
  );
}
