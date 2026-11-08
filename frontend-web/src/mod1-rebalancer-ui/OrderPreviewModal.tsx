"use client";

/**
 * OrderPreviewModal.tsx — Confirmación del rebalanceo antes de ejecutarlo.
 *
 * Resume las órdenes propuestas (ventas primero, luego compras) y el ajuste neto de efectivo. Al confirmar, el
 * padre llama a POST /rebalance/execute: el backend recalcula el plan, genera los asientos de partida doble y
 * registra los movimientos, que aparecen en «Transferencias». Los importes los calcula el backend; aquí solo se pintan.
 *
 * @module mod1-rebalancer-ui/OrderPreviewModal
 */

import React, { useEffect } from "react";
import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";
import { formatEur, formatUnits } from "@/core-ui/format";

interface OrderPreviewModalProps {
  plan: RebalancePlan;
  /** true mientras se envía la ejecución al backend. */
  submitting: boolean;
  /** Error de la ejecución (p. ej. 409 si ya no hay nada que operar). */
  error: string | null;
  onConfirm: () => void;
  onCancel: () => void;
}

export function OrderPreviewModal({
  plan,
  submitting,
  error,
  onConfirm,
  onCancel,
}: OrderPreviewModalProps) {
  const { orders, cashFlow } = plan;

  // Esc cierra el modal (salvo mientras se está ejecutando)
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !submitting) onCancel();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [submitting, onCancel]);

  const net = cashFlow.netCashFlow;
  const cashLabel = net > 0 ? "Entrada de efectivo" : net < 0 ? "Salida de efectivo" : "Sin movimiento de efectivo";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="order-preview-title"
    >
      <div className="bg-surface-raised border border-surface-border rounded-xl w-full max-w-2xl max-h-[90vh] overflow-y-auto">
        <div className="px-5 pt-5 pb-3">
          <h2 id="order-preview-title" className="text-lg font-semibold text-fg">
            Confirmar rebalanceo
          </h2>
          <p className="text-sm text-gray-500 mt-0.5">
            Se ejecutarán {orders.length} órdenes. Quedarán registradas como movimientos en «Transferencias».
          </p>
        </div>

        <ul className="px-5 divide-y divide-surface-border/60">
          {orders.map((o) => {
            const isBuy = o.side === "BUY";
            return (
              <li
                key={`${o.side}-${o.instrumentId}`}
                className="flex items-center justify-between gap-3 py-2.5"
                data-testid={`preview-${o.side}-${o.instrumentId}`}
              >
                <div className="flex items-center gap-3 min-w-0">
                  <span
                    className={`shrink-0 inline-flex px-2 py-0.5 rounded text-xs font-semibold ${
                      isBuy
                        ? "bg-semantic-gain/15 text-semantic-gain"
                        : "bg-semantic-loss/15 text-semantic-loss"
                    }`}
                  >
                    {isBuy ? "COMPRAR" : "VENDER"}
                  </span>
                  <div className="flex flex-col min-w-0">
                    <span className="text-sm text-fg truncate">{o.name}</span>
                    <span className="text-xs text-gray-500 font-mono">
                      {formatUnits(o.quantity)} uds. × {formatEur(o.price)}
                    </span>
                  </div>
                </div>
                <span
                  className={`font-mono font-semibold text-sm shrink-0 ${
                    isBuy ? "text-semantic-gain" : "text-semantic-loss"
                  }`}
                >
                  {isBuy ? "+" : "-"}
                  {formatEur(o.amount)}
                </span>
              </li>
            );
          })}
        </ul>

        <div className="mx-5 mt-3 flex items-center justify-between rounded-lg bg-surface-overlay/40 px-3 py-2.5">
          <span className="text-sm text-gray-400">
            {cashLabel} (caja {formatEur(cashFlow.cashBefore)} → {formatEur(cashFlow.cashAfter)})
          </span>
          <span className="font-mono font-semibold text-sm text-fg" data-testid="preview-cash">
            {net > 0 ? "+" : net < 0 ? "-" : ""}
            {formatEur(Math.abs(net))}
          </span>
        </div>

        {error && (
          <p className="mx-5 mt-3 text-sm text-semantic-loss" role="alert">
            {error}
          </p>
        )}

        <div className="flex justify-end gap-2 px-5 py-4">
          <button
            type="button"
            onClick={onCancel}
            disabled={submitting}
            className="px-4 py-2 rounded-lg text-sm font-medium bg-surface-overlay text-gray-300 hover:text-fg disabled:opacity-50"
          >
            Cancelar
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={submitting || orders.length === 0}
            className="px-4 py-2 rounded-lg text-sm font-medium bg-brand-600 text-white hover:bg-brand-500 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? "Ejecutando..." : "Confirmar y ejecutar"}
          </button>
        </div>
      </div>
    </div>
  );
}
