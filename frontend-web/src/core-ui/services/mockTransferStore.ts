/**
 * mockTransferStore.ts
 *
 * Store en memoria de sesión para el historial de transferencias en modo mock.
 * Empieza vacío (igual que una cartera nueva sin operaciones).
 * El RebalanceDashboard añade entradas al "ejecutar" el rebalanceo simulado.
 *
 * Al recargar la página el store vuelve a [] — comportamiento correcto para un
 * sistema sin backend: no hay persistencia entre sesiones.
 *
 * @module core-ui/services/mockTransferStore
 */

import type { Transfer } from "@/core-ui/types/transfers";

// Estado interno del módulo — persiste mientras la pestaña está abierta
let _transfers: Transfer[] = [];
let _nextId = 1;

/** Devuelve una copia del historial, el más reciente primero. */
export function getMockTransfers(): Transfer[] {
  return [..._transfers];
}

/**
 * Genera y registra las transferencias de un rebalanceo simulado a partir
 * del plan actual (órdenes de compra/venta + ajuste de efectivo).
 * Devuelve las nuevas entradas añadidas.
 */
export function addMockRebalanceTransfers(
  portfolioId: string,
  orders: { instrumentId: string; name: string; side: "BUY" | "SELL"; quantity: number; amount: number; currency: string }[],
  netCashFlow: number,
): Transfer[] {
  const executionId = `MOCK-EXEC-${String(_nextId).padStart(4, "0")}`;
  const now = new Date().toISOString();
  const settles = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString(); // T+2

  const newTransfers: Transfer[] = orders.map((o) => ({
    id: `MOCK-TRF-${String(_nextId++).padStart(4, "0")}`,
    executionId,
    portfolioId,
    createdAt: now,
    settlesAt: settles,
    type: o.side === "BUY" ? "BUY" : "SELL",
    instrumentId: o.instrumentId,
    name: o.name,
    quantity: o.quantity,
    amount: o.amount,
    currency: o.currency,
    journalRef: `JNL-MOCK-${String(_nextId).padStart(4, "0")}`,
    status: "IN_PROGRESS",
  }));

  // Ajuste neto de efectivo si hay movimiento de caja
  if (Math.abs(netCashFlow) > 0.01) {
    newTransfers.push({
      id: `MOCK-TRF-${String(_nextId++).padStart(4, "0")}`,
      executionId,
      portfolioId,
      createdAt: now,
      settlesAt: settles,
      type: netCashFlow > 0 ? "CASH_IN" : "CASH_OUT",
      instrumentId: "CASH:EUR",
      name: "Ajuste neto de efectivo",
      quantity: null,
      amount: Math.abs(netCashFlow),
      currency: "EUR",
      journalRef: `JNL-MOCK-${String(_nextId).padStart(4, "0")}`,
      status: "IN_PROGRESS",
    });
  }

  // Las más recientes primero
  _transfers = [...newTransfers, ..._transfers];
  return newTransfers;
}

/** Simula la liquidación: pasa todos los IN_PROGRESS a COMPLETED. */
export function settleMockTransfers(): void {
  _transfers = _transfers.map((t) =>
    t.status === "IN_PROGRESS" ? { ...t, status: "COMPLETED" } : t,
  );
}

/** Limpia el store (útil para tests). */
export function clearMockTransfers(): void {
  _transfers = [];
  _nextId = 1;
}
