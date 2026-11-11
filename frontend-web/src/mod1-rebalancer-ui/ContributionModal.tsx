"use client";

/**
 * ContributionModal.tsx — «Invertir en Agrupación»: aportación de efectivo a una cesta de fondos.
 *
 * Dos pasos, sin ejecutar nada hasta el final:
 *   1. IMPORTE: el cliente escribe cuánto ingresa (p. ej. 5.000 €) → «Calcular» pide la VISTA PREVIA al backend
 *      (POST .../contribution/preview, sin efectos).
 *   2. CONFIRMACIÓN: «¿Deseas rebalancear con esta nueva aportación de caja?» con el reparto por fondo. Solo se
 *      compra (nunca se vende). Al confirmar → POST .../contribution: asientos, movimientos y cartera actualizada.
 *
 * La UI no calcula el reparto: lo hace el backend (CashInflowAllocator). Aquí solo se valida el formato del importe.
 *
 * @module mod1-rebalancer-ui/ContributionModal
 */

import React, { useEffect, useRef, useState } from "react";
import type { Basket, ContributionExecution, ContributionPlan } from "@/core-ui/types/baskets";
import { executeContribution, previewContribution } from "@/core-ui/services/portfolioService";
import { formatEur, formatUnits, formatWeight } from "@/core-ui/format";

/**
 * Importe en formato español → número, o null si no es válido. Acepta "5000", "5.000", "5000,50", "5.000,50",
 * "5000.50" y un "€" opcional. Máximo 2 decimales. (Un punto seguido de exactamente 3 dígitos se lee como
 * separador de miles, como en es-ES: "5.000" son cinco mil.)
 */
export function parseAmount(raw: string): number | null {
  let s = raw.replace(/[€\s]/g, "");
  if (s === "") return null;
  if (s.includes(",")) {
    s = s.replace(/\./g, "").replace(",", ".");
  } else if (/^\d{1,3}(\.\d{3})+$/.test(s)) {
    s = s.replace(/\./g, "");
  }
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null;
  const n = Number(s);
  return Number.isFinite(n) && n > 0 ? n : null;
}

interface ContributionModalProps {
  portfolioId: string;
  basket: Basket;
  /** Importe sugerido al abrir (p. ej. 5.000). */
  defaultAmount?: string;
  onClose: () => void;
  /** Se llama tras ejecutar con éxito; el padre cierra el modal y recarga la cartera. */
  onExecuted: (execution: ContributionExecution) => void;
}

export function ContributionModal({
  portfolioId,
  basket,
  defaultAmount = "5000",
  onClose,
  onExecuted,
}: ContributionModalProps) {
  const [raw, setRaw] = useState(defaultAmount);
  const [plan, setPlan] = useState<ContributionPlan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const amount = parseAmount(raw);
  const amountInvalid = raw.trim() !== "" && amount === null;

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Esc cierra el modal (salvo mientras se calcula o ejecuta)
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !busy) onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  async function handlePreview() {
    if (amount === null) return;
    setBusy(true);
    setError(null);
    try {
      setPlan(await previewContribution(portfolioId, basket.id, amount));
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo calcular la aportación.");
    } finally {
      setBusy(false);
    }
  }

  async function handleConfirm() {
    if (amount === null) return;
    setBusy(true);
    setError(null);
    try {
      onExecuted(await executeContribution(portfolioId, basket.id, amount));
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo ejecutar la aportación.");
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/60 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="contribution-title"
      data-testid="contribution-modal"
    >
      <div className="bg-surface-raised border border-surface-border rounded-t-2xl sm:rounded-xl w-full max-w-2xl max-h-[90vh] max-h-[90dvh] overflow-y-auto overscroll-contain">
        <div className="px-5 pt-5 pb-3">
          <h2 id="contribution-title" className="text-lg font-semibold text-fg">
            Invertir en {basket.name}
          </h2>
          <p className="text-sm text-gray-500 mt-0.5">{basket.description}</p>
        </div>

        {/* ── Paso 1: importe ── */}
        {plan === null && (
          <div className="px-5 pb-2 flex flex-col gap-3">
            <label className="flex flex-col gap-1.5" htmlFor="contribution-amount">
              <span className="text-sm text-gray-400">Importe a ingresar</span>
              <span className="flex items-center gap-2">
                <input
                  id="contribution-amount"
                  ref={inputRef}
                  type="text"
                  inputMode="decimal"
                  autoComplete="off"
                  value={raw}
                  onChange={(e) => setRaw(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && amount !== null && !busy) void handlePreview();
                  }}
                  disabled={busy}
                  aria-invalid={amountInvalid}
                  className={[
                    "w-48 text-right font-mono text-lg bg-surface-base border rounded-md px-3 py-2 text-fg",
                    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500",
                    amountInvalid ? "border-semantic-loss" : "border-surface-border",
                  ].join(" ")}
                />
                <span className="text-lg text-gray-400">€</span>
              </span>
            </label>
            <p className={`text-xs ${amountInvalid ? "text-semantic-loss" : "text-gray-500"}`} role="status">
              {amountInvalid
                ? "Importe no válido: usa números positivos con hasta 2 decimales (p. ej. 5.000 o 2500,50)."
                : amount !== null
                  ? `Se ingresarán ${formatEur(amount)} y se repartirán entre los fondos de la agrupación.`
                  : "Escribe el importe que quieres ingresar."}
            </p>

            <div className="rounded-lg bg-surface-overlay/40 px-3 py-2 text-xs text-gray-500">
              <p className="mb-1 font-semibold text-gray-400">Composición objetivo de la agrupación</p>
              <p>
                {Object.entries(basket.weights)
                  .map(([, w]) => formatWeight(w))
                  .join(" · ")}
                {" — "}solo se compra: nunca se vende nada.
              </p>
            </div>
          </div>
        )}

        {/* ── Paso 2: vista previa + confirmación ── */}
        {plan !== null && (
          <div className="px-5 pb-2 flex flex-col gap-3" data-testid="contribution-preview">
            <div className="rounded-lg border border-brand-500/40 bg-brand-500/10 px-4 py-3">
              <p className="text-sm font-semibold text-fg" data-testid="contribution-question">
                ¿Deseas rebalancear con esta nueva aportación de caja?
              </p>
              <p className="text-sm text-gray-400 mt-0.5">
                Se ingresarán <span className="font-mono text-fg">{formatEur(plan.amount)}</span> y se invertirán{" "}
                <span className="font-mono text-fg">{formatEur(plan.invested)}</span> comprando{" "}
                {plan.orders.length} {plan.orders.length === 1 ? "fondo" : "fondos"} para acercar la agrupación a sus
                pesos objetivo.
                {plan.unallocated > 0 && (
                  <> Quedan {formatEur(plan.unallocated)} en efectivo por el redondeo de participaciones.</>
                )}
              </p>
            </div>

            <div className="table-scroll">
              <table className="w-full text-sm" aria-label="Reparto de la aportación por fondo">
                <thead>
                  <tr className="border-b border-surface-border text-xs text-gray-500 uppercase tracking-wider">
                    <th className="text-left font-semibold py-2 pr-3">Fondo</th>
                    <th className="text-right font-semibold py-2 px-3">Peso en agrupación</th>
                    <th className="text-right font-semibold py-2 px-3">Participaciones</th>
                    <th className="text-right font-semibold py-2 pl-3">Compra</th>
                  </tr>
                </thead>
                <tbody>
                  {plan.funds.map((f) => (
                    <tr key={f.instrumentId} className="border-b border-surface-border/60" data-testid={`fund-${f.instrumentId}`}>
                      <td className="py-2 pr-3">
                        <span className="text-fg">{f.name}</span>
                      </td>
                      <td className="py-2 px-3 text-right font-mono text-gray-400 whitespace-nowrap">
                        {formatWeight(f.currentWeightInBasket)} → {" "}
                        <span className="text-fg">{formatWeight(f.weightInBasketAfter)}</span>
                        <span className="text-gray-500"> / obj. {formatWeight(f.basketWeight)}</span>
                      </td>
                      <td className="py-2 px-3 text-right font-mono text-fg">
                        {f.buyQuantity > 0 ? formatUnits(f.buyQuantity) : "—"}
                      </td>
                      <td
                        className={`py-2 pl-3 text-right font-mono font-semibold ${
                          f.buyAmount > 0 ? "text-semantic-gain" : "text-gray-500"
                        }`}
                      >
                        {f.buyAmount > 0 ? `+${formatEur(f.buyAmount)}` : "Sin compra"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="flex items-center justify-between rounded-lg bg-surface-overlay/40 px-3 py-2.5 text-sm">
              <span className="text-gray-400">
                Efectivo de la cartera: {formatEur(plan.cashBefore)} → {formatEur(plan.cashAfter)}
              </span>
              <span className="font-mono font-semibold text-fg">
                Valor total {formatEur(plan.projectedPerformance.totalValue)}
              </span>
            </div>
          </div>
        )}

        {error && (
          <p className="mx-5 mt-3 text-sm text-semantic-loss" role="alert">
            {error}
          </p>
        )}

        <div className="flex justify-end gap-2 px-5 py-4">
          {plan === null ? (
            <>
              <button
                type="button"
                onClick={onClose}
                disabled={busy}
                className="px-4 py-2 rounded-lg text-sm font-medium bg-surface-overlay text-gray-300 hover:text-fg disabled:opacity-50"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={() => void handlePreview()}
                disabled={busy || amount === null}
                className="px-4 py-2 rounded-lg text-sm font-medium bg-brand-600 text-white hover:bg-brand-500 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {busy ? "Calculando..." : "Calcular"}
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                onClick={() => {
                  setPlan(null);
                  setError(null);
                }}
                disabled={busy}
                className="px-4 py-2 rounded-lg text-sm font-medium bg-surface-overlay text-gray-300 hover:text-fg disabled:opacity-50"
              >
                Cambiar importe
              </button>
              <button
                type="button"
                onClick={onClose}
                disabled={busy}
                className="px-4 py-2 rounded-lg text-sm font-medium bg-surface-overlay text-gray-300 hover:text-fg disabled:opacity-50"
              >
                No, cancelar
              </button>
              <button
                type="button"
                onClick={() => void handleConfirm()}
                disabled={busy}
                className="px-4 py-2 rounded-lg text-sm font-medium bg-brand-600 text-white hover:bg-brand-500 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {busy ? "Invirtiendo..." : "Sí, rebalancear e invertir"}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
