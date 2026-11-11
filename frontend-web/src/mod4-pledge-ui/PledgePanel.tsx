
"use client";

/**
 * PledgePanel.tsx — Módulo 4: Gestor de pignoración / LTV
 *
 * Flujo en tres pasos:
 *   1. El cliente pega (o carga) su contrato y Claude extrae los términos
 *      con citas literales.
 *   2. El cliente revisa los términos y confirma su deuda: se guardan en el
 *      backend Java.
 *   3. El backend calcula el estado (LTV, distancia al margin call,
 *      acciones) y se puede simular una caída de la renta variable.
 *      Claude lo explica a petición.
 *
 * @module mod4-pledge-ui/PledgePanel
 */

import React, { useState } from "react";
import { formatEur } from "@/core-ui/format";

import {
  explainStatus,
  extractTerms,
  fetchStatus,
  loadSampleContract,
  saveContract,
  toContractRequest,
} from "@/core-ui/services/pledgeService";

import type {
  Explanation,
  ExtractionResult,
  PledgeAssetClass,
  PledgeStatus,
  PledgeZone,
} from "@/core-ui/types/pledge";

const CLASS_LABEL: Record<PledgeAssetClass, string> = {
  EQUITY: "Renta variable",
  FIXED_INCOME: "Renta fija",
  COMMODITY: "Materias primas",
  CASH: "Efectivo",
};

const ZONE_STYLE: Record<
  PledgeZone,
  { label: string; text: string; bar: string }
> = {
  OK: {
    label: "Dentro del límite",
    text: "text-semantic-gain",
    bar: "bg-semantic-gain",
  },
  LIMIT_EXCEEDED: {
    label: "Límite superado",
    text: "text-semantic-warning",
    bar: "bg-semantic-warning",
  },
  MARGIN_CALL: {
    label: "Margin call",
    text: "text-semantic-loss",
    bar: "bg-semantic-loss",
  },
};

const pct = (x: number, decimals = 1) =>
  `${(x * 100).toFixed(decimals)} %`;

interface PledgePanelProps {
  portfolioId?: string;
}

export function PledgePanel({
  portfolioId = "PF-001",
}: PledgePanelProps) {
  const [contractText, setContractText] = useState("");
  const [extraction, setExtraction] = useState<ExtractionResult | null>(null);
  const [loanBalance, setLoanBalance] = useState("65000");
  const [status, setStatus] = useState<PledgeStatus | null>(null);
  const [shock, setShock] = useState(0);
  const [explanation, setExplanation] = useState<Explanation | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run(label: string, task: () => Promise<void>) {
    setBusy(label);
    setError(null);

    try {
      await task();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  const handleLoadSample = () =>
    run("Cargando contrato…", async () =>
      setContractText(await loadSampleContract())
    );

  const handleExtract = () =>
    run("Claude está leyendo el contrato…", async () => {
      setExtraction(null);
      setStatus(null);
      setExplanation(null);
      setExtraction(await extractTerms(contractText));
    });

  const handleConfirm = () =>
    run("Calculando…", async () => {
      if (!extraction) return;

      await saveContract(
        portfolioId,
        toContractRequest(
          extraction.terminos,
          Number(loanBalance)
        )
      );

      setExplanation(null);
      setStatus(await fetchStatus(portfolioId, shock));
    });

  const handleShock = (value: number) => {
    setShock(value);

    if (!status) return;

    void run("Recalculando…", async () => {
      setExplanation(null);
      setStatus(await fetchStatus(portfolioId, value));
    });
  };

  const handleExplain = () =>
    run("Generando explicación…", async () => {
      if (status) {
        setExplanation(await explainStatus(status));
      }
    });

  const t = extraction?.terminos;

  return (
    <div className="flex flex-col gap-6">

      {/* ── 1. Contrato ───────────────────────────────────────────── */}

      <section className="rounded-xl border border-surface-border bg-surface-raised p-6">
        <h2 className="text-lg font-semibold text-white">
          1. Tu contrato de pignoración
        </h2>

        <p className="mt-1 text-sm text-gray-400">
          Pega el texto del contrato. Claude extrae el límite, el margin call
          y los descuentos, cada uno con la frase exacta del contrato que lo
          respalda.
        </p>

        <textarea
          value={contractText}
          onChange={(e) => setContractText(e.target.value)}
          rows={8}
          placeholder="Texto del contrato…"
          className="mt-4 w-full rounded-md bg-surface-overlay border border-surface-border p-3 font-mono text-xs text-gray-200"
        />

        <div className="mt-3 flex flex-wrap gap-3">
          <button
            onClick={handleLoadSample}
            disabled={busy !== null}
            className="rounded-md border border-surface-border px-4 py-2 text-sm text-gray-200 hover:bg-surface-overlay disabled:opacity-50"
          >
            Cargar contrato de ejemplo
          </button>

          <button
            onClick={handleExtract}
            disabled={busy !== null || !contractText.trim()}
            className="rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-500 disabled:opacity-50"
          >
            Extraer términos con Claude
          </button>
        </div>
      </section>

      {busy && (
        <p className="text-sm text-gray-400 animate-pulse">
          {busy}
        </p>
      )}

      {error && (
        <p className="text-sm text-semantic-loss">
          {error}
        </p>
      )}

      {/* ── 2. Revisión ───────────────────────────────────────────── */}

      {t && (
        <section className="rounded-xl border border-surface-border bg-surface-raised p-6">
          <div className="flex items-center justify-between gap-4">
            <h2 className="text-lg font-semibold text-white">
              2. Revisa los términos
            </h2>

            <span className="text-xs text-gray-500">
              {extraction.desde_cache
                ? "Leído de la caché (sin coste)"
                : `Extraído por Claude · ${extraction.intentos} intento(s) · citas verificadas`}
            </span>
          </div>

          <table className="mt-4 w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-gray-500">
                <th className="py-2 pr-4 font-normal">Término</th>
                <th className="py-2 pr-4 font-normal">Valor</th>
                <th className="py-2 font-normal">
                  Cita literal del contrato
                </th>
              </tr>
            </thead>

            <tbody className="text-gray-200">
              <TermRow
                label="Límite de disposición"
                value={t.ltv_maximo === null ? "—" : pct(t.ltv_maximo, 0)}
                quote={t.cita_ltv_maximo}
              />

              <TermRow
                label="Margin call"
                value={
                  t.ltv_margin_call === null
                    ? "—"
                    : pct(t.ltv_margin_call, 0)
                }
                quote={t.cita_margin_call}
              />

              <TermRow
                label="Plazo para reponer"
                value={
                  t.plazo_reposicion_dias_habiles === null
                    ? "—"
                    : `${t.plazo_reposicion_dias_habiles} días hábiles`
                }
                quote={t.cita_plazo}
              />

              {t.haircuts.map((h) => (
                <TermRow
                  key={h.clase}
                  label={`Descuento ${CLASS_LABEL[h.clase].toLowerCase()}`}
                  value={`${pct(h.haircut, 0)} (cuenta el ${pct(1 - h.haircut, 0)})`}
                  quote={h.cita}
                />
              ))}
            </tbody>
          </table>

          <div className="mt-5 flex flex-wrap items-end gap-3">
            <label className="flex flex-col gap-1 text-xs text-gray-400">
              Saldo dispuesto del préstamo (EUR)

              <input
                type="number"
                min="0"
                step="any"
                value={loanBalance}
                onChange={(e) => setLoanBalance(e.target.value)}
                className="rounded-md bg-surface-overlay border border-surface-border px-3 py-2 text-sm text-white"
              />
            </label>

            <button
              onClick={handleConfirm}
              disabled={busy !== null}
              className="rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-500 disabled:opacity-50"
            >
              Confirmar y calcular
            </button>
          </div>
        </section>
      )}

      {/* ── 3. Estado ─────────────────────────────────────────────── */}

      {status && (
        <section className="rounded-xl border border-surface-border bg-surface-raised p-6">
          <div className="flex items-center justify-between gap-4">
            <h2 className="text-lg font-semibold text-white">
              3. Estado de tu préstamo
            </h2>

            <span
              className={`text-sm font-semibold ${ZONE_STYLE[status.zone].text}`}
            >
              {ZONE_STYLE[status.zone].label}
            </span>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-4 md:grid-cols-4">
            <Kpi
              label="Valor de mercado"
              value={formatEur(status.marketValue)}
            />

            <Kpi
              label="Valor de garantía"
              value={formatEur(status.collateralValue)}
            />

            <Kpi
              label="Deuda"
              value={formatEur(status.loanBalance)}
            />

            <Kpi
              label="LTV"
              value={pct(status.ltv)}
              valueClass={ZONE_STYLE[status.zone].text}
            />
          </div>

          <LtvBar status={status} />

          <label className="mt-6 flex flex-col gap-2 text-sm text-gray-300">
            Simular caída de la renta variable:
            <strong className="text-white">
              {pct(shock, 0)}
            </strong>

            <input
              type="range"
              min={0}
              max={0.5}
              step={0.05}
              value={shock}
              onChange={(e) => handleShock(Number(e.target.value))}
              className="w-full max-w-md"
            />
          </label>

          <ul className="mt-5 flex flex-col gap-2 text-sm text-gray-300">
            <li>
              {status.zone === "MARGIN_CALL"
                ? "El LTV ha alcanzado el umbral de margin call."
                : status.equityDropToMarginCall === null
                  ? "Ni una caída del 100 % de la renta variable provocaría margin call."
                  : `La renta variable puede caer otro ${pct(status.equityDropToMarginCall)} antes del margin call.`}
            </li>

            <li>
              Probabilidad estimada de margin call en {status.horizonDays} días:{" "}
              <strong className="text-white">
                {pct(status.marginCallProbability, 2)}
              </strong>{" "}
              <span className="text-xs text-gray-500">
                (modelo browniano, volatilidad supuesta de la garantía{" "}
                {pct(status.collateralVolatility)})
              </span>
            </li>
          </ul>

          {status.actions.repayCash > 0 && (
            <div className="mt-5">
              <p className="text-sm font-medium text-white">
                Opciones para volver al límite
              </p>

              <ul className="mt-2 grid grid-cols-1 gap-3 md:grid-cols-3">
                <Kpi
                  label="Amortizar con efectivo"
                  value={formatEur(status.actions.repayCash)}
                />

                <Kpi
                  label="Aportar efectivo como garantía"
                  value={formatEur(status.actions.addCollateral)}
                />

                {Object.entries(status.actions.sellByClass).map(
                  ([cls, sale]) =>
                    sale && sale.feasible ? (
                      <Kpi
                        key={cls}
                        label={`Vender ${CLASS_LABEL[cls as PledgeAssetClass].toLowerCase()}`}
                        value={formatEur(sale.marketValueToSell)}
                      />
                    ) : null
                )}
              </ul>
            </div>
          )}

          <button
            onClick={handleExplain}
            disabled={busy !== null}
            className="mt-6 rounded-md border border-surface-border px-4 py-2 text-sm text-gray-200 hover:bg-surface-overlay disabled:opacity-50"
          >
            Explicar con Claude
          </button>

          {explanation && (
            <div className="mt-4 rounded-lg bg-surface-overlay p-4 text-sm text-gray-200">
              <p>{explanation.texto}</p>

              <p className="mt-2 text-xs text-gray-500">
                {explanation.fuente === "claude"
                  ? "Explicación generada por Claude · cifras verificadas contra el motor"
                  : "Explicación automática (sin IA)"}
              </p>
            </div>
          )}
        </section>
      )}
    </div>
  );
}

function TermRow(props: {
  label: string;
  value: string;
  quote: string | null;
}) {
  return (
    <tr className="border-t border-surface-border align-top">
      <td className="py-2 pr-4 text-gray-400">
        {props.label}
      </td>

      <td className="py-2 pr-4 font-medium text-white whitespace-nowrap">
        {props.value}
      </td>

      <td className="py-2 text-xs italic text-gray-400">
        {props.quote
          ? `«${props.quote}»`
          : "No aparece en el contrato"}
      </td>
    </tr>
  );
}

function LtvBar({ status }: { status: PledgeStatus }) {
  const width = Math.min(status.ltv, 1) * 100;

  return (
    <div className="mt-6 max-w-xl">
      <div className="relative h-3 rounded-full bg-surface-overlay overflow-hidden">
        <div
          className={`h-full rounded-full transition-all duration-300 ${ZONE_STYLE[status.zone].bar}`}
          style={{ width: `${width}%` }}
        />

        <div
          className="absolute top-0 bottom-0 w-0.5 bg-semantic-warning"
          style={{ left: `${status.ltvMax * 100}%` }}
        />

        <div
          className="absolute top-0 bottom-0 w-0.5 bg-semantic-loss"
          style={{ left: `${status.ltvMarginCall * 100}%` }}
        />
      </div>

      <div className="mt-1 flex justify-between text-xs text-gray-500">
        <span>0 %</span>

        <span className="text-semantic-warning">
          Límite {pct(status.ltvMax, 0)}
        </span>

        <span className="text-semantic-loss">
          Margin call {pct(status.ltvMarginCall, 0)}
        </span>

        <span>100 %</span>
      </div>
    </div>
  );
}

function Kpi(props: {
  label: string;
  value: string;
  valueClass?: string;
}) {
  return (
    <div className="rounded-lg bg-surface-overlay p-4">
      <p className="text-xs text-gray-400">
        {props.label}
      </p>

      <p
        className={`mt-1 text-lg font-semibold ${props.valueClass ?? "text-white"}`}
      >
        {props.value}
      </p>
    </div>
  );
}
