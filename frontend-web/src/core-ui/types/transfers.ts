/**
 * Tipos del historial de transferencias — espejo de los records Java `Transfer` y `RebalanceExecution`
 * (com.wealth.mod1_rebalancer). Endpoints:
 *   POST /api/v1/portfolios/{id}/rebalance/execute  → RebalanceExecution
 *   GET  /api/v1/portfolios/{id}/transfers          → Transfer[] (el más reciente primero)
 *
 * @module core-ui/types/transfers
 */

import type { CashFlowSummary } from "@/core-ui/types/rebalancePlan";

/**
 * BUY / SELL: una por orden. CASH_IN / CASH_OUT: ajuste NETO de efectivo de la ejecución
 * (ventas - compras): es el resumen del efecto en caja, no un movimiento adicional de dinero.
 * CONTRIBUTION: aportación de efectivo del cliente a una agrupación (ingreso, liquidado al instante).
 */
export type TransferType = "BUY" | "SELL" | "CASH_IN" | "CASH_OUT" | "CONTRIBUTION";

/** El backend lo calcula al leer: IN_PROGRESS hasta la liquidación simulada y COMPLETED después. */
export type TransferStatus = "IN_PROGRESS" | "COMPLETED";

export interface Transfer {
  id: string;
  executionId: string;
  portfolioId: string;
  /** ISO 8601 instant */
  createdAt: string;
  /** ISO 8601 instant: cuándo pasa a COMPLETED */
  settlesAt: string;
  type: TransferType;
  instrumentId: string;
  name: string;
  /** Participaciones (máx. 4 decimales). null en los movimientos de efectivo. */
  quantity: number | null;
  /** Siempre positivo; el sentido lo da `type`. */
  amount: number;
  currency: string;
  /** Asiento de partida doble asociado (OpenJEV journal). */
  journalRef: string;
  status: TransferStatus;
}

export interface RebalanceExecution {
  executionId: string;
  portfolioId: string;
  executedAt: string;
  journalReference: string;
  transfers: Transfer[];
  cashFlow: CashFlowSummary;
}
