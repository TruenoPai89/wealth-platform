"use client";

/**
 * TwoMonthWarningBadge.tsx — Módulo 2: Optimizador Fiscal
 *
 * Badge de alerta que se inyecta en el "StatusBadgeSlot" de la tabla de posiciones
 * cuando una posición tiene lotes comprados hace menos de 2 meses.
 *
 * REGLA AEAT (Art. 33.5 LIRPF): Si vendes con pérdida un valor y lo recompras
 * en los 2 meses anteriores/posteriores, la pérdida NO es deducible en ese ejercicio.
 *
 * CÓMO SE CONECTA A LA TABLA (Patrón de Slots):
 *   Este componente NO se importa en PositionsTableWithSlots.tsx.
 *   En su lugar, el bootstrap del módulo hace:
 *     useRegisterSlot("StatusBadgeSlot", TwoMonthWarningBadge, { order: 1 });
 *   Y el Slot renderiza este badge automáticamente para cada fila donde
 *   position.flags.taxWarning2Month === true.
 *
 * ESTADO ACTUAL: Implementación completa. Pendiente de registrar en RebalanceDashboard.
 *
 * @module mod2-tax-ui/TwoMonthWarningBadge
 */

import React from "react";
import type { SlotRowProps } from "@/core-ui/SlotRegistry";

export function TwoMonthWarningBadge({ position }: SlotRowProps) {
  // Solo renderiza si esta posición tiene el flag activo
  if (!position.flags?.taxWarning2Month) return null;

  return (
    <span
      className={[
        "inline-flex items-center gap-1 px-2 py-0.5 rounded",
        "text-xs font-semibold",
        "bg-semantic-warning/15 text-semantic-warning",
        "border border-semantic-warning/30",
      ].join(" ")}
      title="Lotes con menos de 2 meses de antigüedad — La venta con pérdida no sería deducible (Art. 33.5 LIRPF)"
      aria-label="Alerta fiscal: regla de los 2 meses"
    >
      {/* Icono de reloj */}
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
        <circle cx="12" cy="12" r="10" />
        <polyline points="12 6 12 12 16 14" />
      </svg>
      2M
    </span>
  );
}
