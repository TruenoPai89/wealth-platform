"use client";

/**
 * BasketsPanel.tsx — Agrupaciones de fondos (cestas / model portfolios).
 *
 * Una tarjeta por agrupación con su composición objetivo (p. ej. «3 fondos al 33,3 %» o una distribución
 * personalizada) y el botón «Invertir en Agrupación», que abre el modal de aportación. La definición de las
 * cestas viene del backend (solo lectura); aquí no se edita.
 *
 * @module mod1-rebalancer-ui/BasketsPanel
 */

import React from "react";
import type { Basket } from "@/core-ui/types/baskets";
import { formatWeight } from "@/core-ui/format";

interface BasketsPanelProps {
  baskets: Basket[];
  /** instrumentId → nombre del fondo (de la cartera). */
  nameOf: Record<string, string>;
  onInvest: (basket: Basket) => void;
  onCreate: () => void;
  disabled?: boolean;
}

/** "3 fondos al 33,3 %" si todos pesan lo mismo; si no, "4 fondos con distribución personalizada". */
function summary(basket: Basket): string {
  const weights = Object.values(basket.weights);
  const n = weights.length;
  const first = weights[0] ?? 0;
  const equal = weights.every((w) => Math.abs(w - first) < 0.0005);
  const noun = n === 1 ? "fondo" : "fondos";
  return equal
    ? `${n} ${noun} al ${(first * 100).toFixed(1).replace(".", ",")} %`
    : `${n} ${noun} con distribución personalizada`;
}

export function BasketsPanel({ baskets, nameOf, onInvest, onCreate, disabled = false }: BasketsPanelProps) {
  return (
    <section aria-label="Agrupaciones de fondos" className="flex flex-col gap-3" data-testid="baskets-panel">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold text-fg">Agrupaciones</h2>
          <p className="mt-0.5 text-sm text-gray-500">Estrategias de fondos para invertir una cantidad o rebalancear tu exposición.</p>
        </div>
        <button type="button" onClick={onCreate} disabled={disabled} className="rounded-lg border border-brand-500/60 px-3 py-2 text-sm font-medium text-brand-300 hover:bg-brand-500/10 disabled:opacity-50">
          + Crear agrupación
        </button>
      </div>

      {baskets.length > 0 && <div className="grid gap-4 md:grid-cols-2">
        {baskets.map((basket) => (
          <article
            key={basket.id}
            className="bg-surface-raised border border-surface-border rounded-xl p-5 flex flex-col gap-4"
            data-testid={`basket-${basket.id}`}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h3 className="text-base font-semibold text-fg">{basket.name}</h3>
                <p className="text-xs text-gray-500 mt-0.5" data-testid="basket-summary">
                  {summary(basket)}
                </p>
              </div>
              <button
                type="button"
                onClick={() => onInvest(basket)}
                disabled={disabled}
                data-testid={`invest-${basket.id}`}
                className="shrink-0 px-3 py-2 rounded-lg text-sm font-medium bg-brand-600 text-white hover:bg-brand-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
              >
                Invertir / rebalancear
              </button>
            </div>

            <ul className="flex flex-col gap-2">
              {Object.entries(basket.weights).map(([id, weight]) => (
                <li key={id} className="flex flex-col gap-1">
                  <div className="flex items-center justify-between gap-3 text-sm">
                    <span className="text-fg truncate" title={nameOf[id] ?? id}>
                      {nameOf[id] ?? id}
                    </span>
                    <span className="font-mono text-gray-400 shrink-0">{formatWeight(weight)}</span>
                  </div>
                  <div className="h-1.5 bg-surface-overlay rounded-full overflow-hidden">
                    <div
                      className="h-full rounded-full bg-brand-500"
                      style={{ width: `${Math.min(weight * 100, 100)}%` }}
                    />
                  </div>
                </li>
              ))}
            </ul>
          </article>
        ))}
      </div>}
    </section>
  );
}
