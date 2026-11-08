"use client";

/**
 * RebalanceOrdersPanel.tsx — Resultado del rebalanceo: órdenes de compra/venta entre fondos.
 *
 * Pinta lo que calcula el backend (RebalancePlan.orders y .cashFlow). La UI no calcula nada.
 *
 * Semántica de colores (por orden):
 *   COMPRAR → badge verde y importe verde con "+"   (+7.500,00 €)
 *   VENDER  → badge rojo  y importe rojo  con "-"   (-5.500,00 €)
 * Participaciones: máximo 4 decimales limpios (52,3810 → "52,381").
 *
 * @module mod1-rebalancer-ui/RebalanceOrdersPanel
 */

import React from "react";
import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";
import { formatEur, formatUnits } from "@/core-ui/format";

interface SummaryItemProps {
  label: string;
  value: string;
  tone?: "neutral" | "gain" | "loss";
}

function SummaryItem({ label, value, tone = "neutral" }: SummaryItemProps) {
  const color =
    tone === "gain" ? "text-semantic-gain" : tone === "loss" ? "text-semantic-loss" : "text-white";
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-xs text-gray-500 uppercase tracking-wider">{label}</span>
      <span className={`text-sm font-semibold font-mono ${color}`}>{value}</span>
    </div>
  );
}

export function RebalanceOrdersPanel({ plan }: { plan: RebalancePlan }) {
  const { orders, cashFlow } = plan;

  return (
    <section
      className="bg-surface-raised border border-surface-border rounded-xl overflow-hidden"
      aria-label="Órdenes de rebalanceo"
    >
      <div className="px-5 pt-5 pb-3">
        <h2 className="text-lg font-semibold text-white">Órdenes de rebalanceo</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          {orders.length === 0
            ? "La cartera ya está en su meta: no hay nada que operar."
            : `${orders.length} órdenes propuestas: primero se venden los activos que sobrepasan su meta y con ese dinero se compran los que se quedan cortos.`}
        </p>
      </div>

      {orders.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm" aria-label="Tabla de órdenes de rebalanceo">
            <thead>
              <tr className="border-y border-surface-border bg-surface-overlay/50">
                {["Operación", "Fondo", "Participaciones", "Precio", "Importe"].map((h, i) => (
                  <th
                    key={h}
                    className={`px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider ${
                      i >= 2 ? "text-right" : "text-left"
                    }`}
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => {
                const isBuy = o.side === "BUY";
                return (
                  <tr
                    key={`${o.side}-${o.instrumentId}`}
                    className="border-b border-surface-border/60"
                    data-testid={`order-${o.side}-${o.instrumentId}`}
                  >
                    <td className="px-4 py-3">
                      <span
                        data-testid="order-badge"
                        className={`inline-flex px-2 py-0.5 rounded text-xs font-semibold ${
                          isBuy
                            ? "bg-semantic-gain/15 text-semantic-gain"
                            : "bg-semantic-loss/15 text-semantic-loss"
                        }`}
                      >
                        {isBuy ? "COMPRAR" : "VENDER"}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col">
                        <span className="text-white">{o.name}</span>
                        <span className="text-xs text-gray-500 font-mono">{o.instrumentId}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-white">
                      {formatUnits(o.quantity)}
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-gray-300">
                      {formatEur(o.price)}
                    </td>
                    <td
                      data-testid="order-amount"
                      className={`px-4 py-3 text-right font-mono font-semibold ${
                        isBuy ? "text-semantic-gain" : "text-semantic-loss"
                      }`}
                    >
                      {isBuy ? "+" : "-"}
                      {formatEur(o.amount)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <div className="grid grid-cols-2 sm:grid-cols-5 gap-4 px-5 py-4 bg-surface-overlay/30">
        <SummaryItem label="Total a comprar" value={`+${formatEur(cashFlow.buyTotal)}`} tone="gain" />
        <SummaryItem label="Total a vender" value={`-${formatEur(cashFlow.sellTotal)}`} tone="loss" />
        <SummaryItem label="Flujo neto de caja" value={formatEur(cashFlow.netCashFlow)} />
        <SummaryItem label="Caja antes" value={formatEur(cashFlow.cashBefore)} />
        <SummaryItem label="Caja después" value={formatEur(cashFlow.cashAfter)} />
      </div>
    </section>
  );
}
