"use client";

/**
 * PortfolioViewToggle.tsx — Alterna entre «Cartera Actual» y «Cartera Proyectada».
 *
 * - Actual:     la cartera hoy (pesos actuales frente a la meta).
 * - Proyectada: cómo queda la cartera tras ejecutar las órdenes propuestas (los pesos se ajustan a la meta).
 *
 * @module mod1-rebalancer-ui/PortfolioViewToggle
 */

import React from "react";
import type { PortfolioViewMode } from "@/core-ui/services/rebalancePlanAdapter";

interface PortfolioViewToggleProps {
  mode: PortfolioViewMode;
  onChange: (mode: PortfolioViewMode) => void;
}

const OPTIONS: { value: PortfolioViewMode; label: string }[] = [
  { value: "current", label: "Cartera Actual" },
  { value: "projected", label: "Cartera Proyectada" },
];

export function PortfolioViewToggle({ mode, onChange }: PortfolioViewToggleProps) {
  return (
    <div
      role="group"
      aria-label="Vista de la cartera"
      className="inline-flex rounded-lg border border-surface-border bg-surface-raised p-0.5"
    >
      {OPTIONS.map((o) => {
        const active = o.value === mode;
        return (
          <button
            key={o.value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(o.value)}
            className={[
              "px-3 py-1.5 rounded-md text-sm font-medium transition-colors duration-150",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500",
              active ? "bg-brand-600 text-white" : "text-gray-400 hover:text-fg",
            ].join(" ")}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
