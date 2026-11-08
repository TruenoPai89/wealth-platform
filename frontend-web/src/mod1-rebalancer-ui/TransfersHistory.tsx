"use client";

/**
 * TransfersHistory.tsx — Sección «Transferencias»: historial de movimientos del rebalanceo.
 *
 * Muestra, el más reciente primero, las compras, ventas y el ajuste de efectivo que genera cada ejecución, con
 * fecha, tipo, importe y estado (Completado / En proceso). El historial vive en el backend (en memoria, ver
 * TransferService): esta vista solo lo consulta. Se refresca sola cada pocos segundos para que los movimientos
 * pasen de «En proceso» a «Completado» sin recargar.
 *
 * La fila «Entrada/Salida de efectivo» es el ajuste NETO de la ejecución (ventas − compras): resume el efecto en
 * caja de las compras y ventas anteriores, no es un movimiento de dinero adicional.
 *
 * @module mod1-rebalancer-ui/TransfersHistory
 */

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { fetchTransfers } from "@/core-ui/services/portfolioService";
import { formatEur, formatUnits } from "@/core-ui/format";
import type { Transfer, TransferStatus, TransferType } from "@/core-ui/types/transfers";

const REFRESH_MS = 5000;

const TYPE_LABEL: Record<TransferType, string> = {
  BUY: "Compra",
  SELL: "Venta",
  CASH_IN: "Entrada de efectivo",
  CASH_OUT: "Salida de efectivo",
};

/** Verde = suma (compra, entrada de caja), rojo = resta (venta, salida): misma semántica que las órdenes. */
const POSITIVE: Record<TransferType, boolean> = {
  BUY: true,
  SELL: false,
  CASH_IN: true,
  CASH_OUT: false,
};

const STATUS_LABEL: Record<TransferStatus, string> = {
  COMPLETED: "Completado",
  IN_PROGRESS: "En proceso",
};

const DATE_FORMAT = new Intl.DateTimeFormat("es-ES", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

function StatusBadge({ status }: { status: TransferStatus }) {
  const done = status === "COMPLETED";
  return (
    <span
      data-testid="transfer-status"
      className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs font-semibold ${
        done ? "bg-semantic-gain/15 text-semantic-gain" : "bg-yellow-500/15 text-yellow-400"
      }`}
    >
      {!done && <span className="w-1.5 h-1.5 rounded-full bg-yellow-400 animate-pulse" aria-hidden="true" />}
      {STATUS_LABEL[status]}
    </span>
  );
}

export function TransfersHistory({ portfolioId }: { portfolioId: string }) {
  const [transfers, setTransfers] = useState<Transfer[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setTransfers(await fetchTransfers(portfolioId));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo cargar el historial.");
    }
  }, [portfolioId]);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const inProgress = transfers?.filter((t) => t.status === "IN_PROGRESS").length ?? 0;

  return (
    <div className="flex flex-col gap-6 max-w-7xl mx-auto w-full">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-white">Transferencias</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Historial de movimientos, transferencias de efectivo y ejecuciones de órdenes
            {inProgress > 0 ? ` · ${inProgress} en proceso` : ""}
          </p>
        </div>
        <Link
          href="/"
          className="px-4 py-2 rounded-lg text-sm font-medium bg-surface-overlay text-gray-200 hover:text-white border border-surface-border"
        >
          Ir a Mis Inversiones
        </Link>
      </div>

      {error && (
        <div className="bg-surface-raised border border-surface-border rounded-xl p-6" role="alert">
          <p className="text-semantic-loss font-medium">Error cargando las transferencias</p>
          <p className="text-gray-500 text-sm mt-1">{error}</p>
        </div>
      )}

      {!error && transfers === null && <p className="text-gray-500 text-sm">Cargando historial...</p>}

      {!error && transfers !== null && transfers.length === 0 && (
        <div className="bg-surface-raised border border-surface-border rounded-xl py-16 flex flex-col items-center gap-2">
          <p className="text-gray-400 font-medium">Todavía no hay movimientos</p>
          <p className="text-gray-600 text-sm text-center max-w-md">
            Los movimientos aparecen aquí cuando confirmas un rebalanceo en «Mis Inversiones».
          </p>
        </div>
      )}

      {!error && transfers !== null && transfers.length > 0 && (
        <div className="bg-surface-raised border border-surface-border rounded-xl overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm" aria-label="Historial de transferencias">
              <thead>
                <tr className="border-b border-surface-border bg-surface-overlay/50">
                  {["Fecha", "Operación", "Detalle", "Importe", "Estado"].map((h, i) => (
                    <th
                      key={h}
                      className={`px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider ${
                        h === "Importe" ? "text-right" : "text-left"
                      } ${i === 4 ? "w-36" : ""}`}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {transfers.map((t) => {
                  const positive = POSITIVE[t.type];
                  const isCash = t.type === "CASH_IN" || t.type === "CASH_OUT";
                  return (
                    <tr
                      key={t.id}
                      className="border-b border-surface-border/60"
                      data-testid={`transfer-${t.type}`}
                    >
                      <td className="px-4 py-3 text-gray-400 whitespace-nowrap">
                        {DATE_FORMAT.format(new Date(t.createdAt))}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          data-testid="transfer-type"
                          className={`inline-flex px-2 py-0.5 rounded text-xs font-semibold ${
                            positive
                              ? "bg-semantic-gain/15 text-semantic-gain"
                              : "bg-semantic-loss/15 text-semantic-loss"
                          }`}
                        >
                          {TYPE_LABEL[t.type]}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex flex-col">
                          <span className="text-white">{t.name}</span>
                          <span className="text-xs text-gray-500 font-mono">
                            {isCash
                              ? "Ajuste neto de la ejecución"
                              : `${formatUnits(t.quantity ?? 0)} uds.`}
                            {" · "}
                            {t.journalRef}
                          </span>
                        </div>
                      </td>
                      <td
                        data-testid="transfer-amount"
                        className={`px-4 py-3 text-right font-mono font-semibold whitespace-nowrap ${
                          positive ? "text-semantic-gain" : "text-semantic-loss"
                        }`}
                      >
                        {positive ? "+" : "-"}
                        {formatEur(t.amount)}
                      </td>
                      <td className="px-4 py-3">
                        <StatusBadge status={t.status} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
