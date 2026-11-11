"use client";

/**
 * AnnualTaxReportPanel.tsx — Módulo 3: resumen fiscal del ejercicio (borrador para el Modelo 100)
 *
 * Solo la parte de ganancias y pérdidas por venta de fondos y ETFs. El cliente revisa las ventas
 * del año (vienen precargadas con un ejemplo), el backend Java calcula FIFO, regla de los 2 meses,
 * compensación, base del ahorro y cuota; Claude lo explica con las cifras verificadas; el cliente
 * puede descargarlo en CSV para trasladarlo a Renta WEB.
 *
 * @module mod3-irpf-ui/AnnualTaxReportPanel
 */

import React, { useState } from "react";
import { formatEur, formatUnits } from "@/core-ui/format";
import { buildAnnualReport, explainAnnualReport } from "@/core-ui/services/irpfService";
import type { AnnualReport, AnnualSale, IrpfExplanation } from "@/core-ui/types/irpf";

const POSITIONS = [
  { isin: "IE00B4L5Y983", name: "iShares Core MSCI World" },
  { isin: "IE00B3XXRP09", name: "Vanguard S&P 500" },
  { isin: "IE00B4WXJJ64", name: "iShares Core Euro Govt Bond" },
  { isin: "IE00B579F325", name: "Invesco Physical Gold" },
  { isin: "IE00B3F81R35", name: "iShares Core UK Gilts" },
];
const nameOf = (isin: string) => POSITIONS.find((p) => p.isin === isin)?.name ?? isin;

/** Ventas de ejemplo de 2026 (lotes de bank-simulator-mocks). La última activa la regla de los 2 meses. */
const DEMO_SALES: AnnualSale[] = [
  { isin: "IE00B4L5Y983", saleDate: "2026-03-16", quantity: 300, salePrice: 95 },
  { isin: "IE00B3XXRP09", saleDate: "2026-05-20", quantity: 600, salePrice: 70 },
  { isin: "IE00B4WXJJ64", saleDate: "2026-06-10", quantity: 400, salePrice: 98.5 },
  { isin: "IE00B3XXRP09", saleDate: "2026-10-05", quantity: 600, salePrice: 72 },
  { isin: "IE00B3F81R35", saleDate: "2026-10-20", quantity: 1000, salePrice: 9.5 },
];

interface AnnualTaxReportPanelProps {
  portfolioId?: string;
  fiscalYear?: number;
}

const inputClass =
  "w-full rounded-md bg-surface-overlay border border-surface-border px-2 py-1.5 text-sm text-fg";

export function AnnualTaxReportPanel({ portfolioId = "PF-001", fiscalYear = 2026 }: AnnualTaxReportPanelProps) {
  const [sales, setSales] = useState<AnnualSale[]>(DEMO_SALES);
  const [carryYear, setCarryYear] = useState("2023");
  const [carryAmount, setCarryAmount] = useState("1500");

  const [report, setReport] = useState<AnnualReport | null>(null);
  const [explanation, setExplanation] = useState<IrpfExplanation | null>(null);
  const [loading, setLoading] = useState(false);
  const [explaining, setExplaining] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function updateSale(i: number, patch: Partial<AnnualSale>) {
    setSales((prev) => prev.map((s, j) => (j === i ? { ...s, ...patch } : s)));
  }

  function addSale() {
    setSales((prev) => [...prev, { isin: POSITIONS[0]!.isin, saleDate: `${fiscalYear}-01-15`, quantity: 100, salePrice: 100 }]);
  }

  function removeSale(i: number) {
    setSales((prev) => prev.filter((_, j) => j !== i));
  }

  async function handleBuild() {
    setLoading(true);
    setError(null);
    setReport(null);
    setExplanation(null);
    try {
      const amount = Number(carryAmount) || 0;
      const r = await buildAnnualReport(portfolioId, {
        fiscalYear,
        sales,
        carryForward: amount > 0 ? [{ originYear: Number(carryYear), amount }] : [],
      });
      setReport(r);
      // Las cifras se muestran ya; la explicación llega después (no bloquea).
      setExplaining(true);
      setExplanation(await explainAnnualReport(r));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
      setExplaining(false);
    }
  }

  return (
    <section className="mt-8 rounded-xl border border-surface-border bg-surface-raised p-4 sm:p-6">
      <h2 className="text-lg font-semibold text-fg">Resumen fiscal de tus inversiones {fiscalYear} (borrador)</h2>
      <p className="mt-1 text-sm text-gray-400">
        Ganancias y pérdidas por venta de fondos y ETFs para la declaración de la renta. No incluye
        trabajo, dividendos, deducciones ni el resultado a pagar o devolver.
      </p>

      {/* ── Ventas del ejercicio ─────────────────────────────────────────── */}
      <h3 className="mt-6 text-sm font-semibold text-fg">Ventas del ejercicio</h3>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-400">
              <th className="py-1 pr-2 font-normal">Posición</th>
              <th className="py-1 pr-2 font-normal">Fecha de venta</th>
              <th className="py-1 pr-2 font-normal">Participaciones</th>
              <th className="py-1 pr-2 font-normal">Precio (EUR)</th>
              <th className="py-1" />
            </tr>
          </thead>
          <tbody>
            {sales.map((s, i) => (
              <tr key={i}>
                <td className="py-1 pr-2 min-w-[12rem]">
                  <select value={s.isin} onChange={(e) => updateSale(i, { isin: e.target.value })} className={inputClass}>
                    {POSITIONS.map((p) => (
                      <option key={p.isin} value={p.isin}>{p.name}</option>
                    ))}
                  </select>
                </td>
                <td className="py-1 pr-2">
                  <input type="date" value={s.saleDate} min={`${fiscalYear}-01-01`} max={`${fiscalYear}-12-31`}
                    onChange={(e) => updateSale(i, { saleDate: e.target.value })} className={inputClass} />
                </td>
                <td className="py-1 pr-2">
                  <input type="number" min="0" step="any" value={s.quantity}
                    onChange={(e) => updateSale(i, { quantity: Number(e.target.value) })} className={inputClass} />
                </td>
                <td className="py-1 pr-2">
                  <input type="number" min="0" step="any" value={s.salePrice}
                    onChange={(e) => updateSale(i, { salePrice: Number(e.target.value) })} className={inputClass} />
                </td>
                <td className="py-1 text-right">
                  <button onClick={() => removeSale(i)} className="text-xs text-gray-400 hover:text-semantic-loss" aria-label="Quitar venta">
                    Quitar
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <button type="button" onClick={addSale} className="mt-2 rounded text-xs font-medium text-accent hover:underline active:opacity-80">
        + Añadir venta
      </button>

      {/* ── Saldos de años anteriores ────────────────────────────────────── */}
      <div className="mt-5 grid grid-cols-2 gap-4 md:max-w-md">
        <label className="flex flex-col gap-1 text-xs text-gray-500">
          <span className="flex min-h-[2rem] items-end leading-4">Pérdidas pendientes de años anteriores: año</span>
          <input type="number" value={carryYear} onChange={(e) => setCarryYear(e.target.value)} className={inputClass} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-gray-500">
          <span className="flex min-h-[2rem] items-end leading-4">Importe pendiente (EUR)</span>
          <input type="number" min="0" step="any" value={carryAmount} onChange={(e) => setCarryAmount(e.target.value)} className={inputClass} />
        </label>
      </div>

      <button
        onClick={handleBuild}
        disabled={loading || sales.length === 0}
        className="mt-5 rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-500 disabled:opacity-50"
      >
        {loading ? "Calculando…" : "Calcular resumen"}
      </button>

      {error && <p className="mt-4 text-sm text-semantic-loss">{error}</p>}

      {report && <ReportView report={report} />}

      {explaining && <p className="mt-4 text-sm text-gray-400 animate-pulse">Generando explicación…</p>}

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
  );
}

// ─── Resultado ────────────────────────────────────────────────────────────────

function ReportView({ report }: { report: AnnualReport }) {
  const r = report.result;
  const net = r.netBalance;
  return (
    <>
      <div className="mt-6 grid grid-cols-2 gap-4 md:grid-cols-5">
        <Kpi label="Ganancias" value={formatEur(report.totalGains)} valueClass="text-semantic-gain" />
        <Kpi label="Pérdidas" value={formatEur(report.totalLosses)} valueClass="text-semantic-loss" />
        <Kpi label="Compensado de años anteriores" value={formatEur(r.carryForwardUsed)} />
        <Kpi label="Base del ahorro" value={formatEur(r.taxableBase)} />
        <Kpi label="Cuota íntegra del ahorro" value={formatEur(r.tax)} valueClass="text-fg" />
      </div>

      {report.deferredLosses > 0 && (
        <p className="mt-3 text-sm text-semantic-warning">
          {formatEur(report.deferredLosses)} de pérdidas no computan este año por la regla de los 2 meses
          (art. 33.5 LIRPF): recompraste el mismo valor en los dos meses anteriores o posteriores a la venta.
        </p>
      )}
      {net < 0 && (
        <p className="mt-3 text-sm text-gray-300">
          Saldo neto negativo de {formatEur(net)}: no hay cuota este año y esa pérdida queda pendiente para
          compensar en los ejercicios siguientes.
        </p>
      )}

      <div className="mt-5 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-400 border-b border-surface-border">
              <th className="py-2 pr-3 font-normal">Venta</th>
              <th className="py-2 pr-3 font-normal">Fondo</th>
              <th className="py-2 pr-3 font-normal text-right">Particip.</th>
              <th className="py-2 pr-3 font-normal">Adquisición</th>
              <th className="py-2 pr-3 font-normal text-right">Valor adquisición</th>
              <th className="py-2 pr-3 font-normal text-right">Valor transmisión</th>
              <th className="py-2 font-normal text-right">Resultado</th>
            </tr>
          </thead>
          <tbody>
            {report.operations.map((op, i) => (
              <tr key={i} className="border-b border-surface-border/60 align-top">
                <td className="py-2 pr-3 text-gray-200">{op.saleDate}</td>
                <td className="py-2 pr-3 text-gray-200">
                  {nameOf(op.isin)}
                  {op.warning && <p className="text-xs text-semantic-warning">{op.warning}</p>}
                </td>
                <td className="py-2 pr-3 text-right text-gray-200">{formatUnits(op.quantityCovered)}</td>
                <td className="py-2 pr-3 text-gray-400">
                  {op.firstAcquisitionDate === op.lastAcquisitionDate
                    ? op.firstAcquisitionDate ?? "—"
                    : `${op.firstAcquisitionDate} … ${op.lastAcquisitionDate}`}
                </td>
                <td className="py-2 pr-3 text-right text-gray-200">{formatEur(op.acquisitionValue)}</td>
                <td className="py-2 pr-3 text-right text-gray-200">{formatEur(op.transmissionValue)}</td>
                <td className={`py-2 text-right font-medium ${
                  op.twoMonthRuleViolation ? "text-gray-400 line-through"
                    : op.gainLoss > 0 ? "text-semantic-gain" : op.gainLoss < 0 ? "text-semantic-loss" : "text-gray-200"}`}>
                  {formatEur(op.gainLoss)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button
          onClick={() => downloadCsv(report)}
          className="rounded-md border border-surface-border px-3 py-1.5 text-xs font-medium text-gray-200 hover:bg-surface-overlay"
        >
          Descargar CSV
        </button>
        <span className="text-xs text-gray-500">
          Borrador orientativo: revisa los datos en Renta WEB antes de presentar. No es asesoramiento fiscal.
        </span>
      </div>
    </>
  );
}

function Kpi(props: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="rounded-lg bg-surface-overlay p-4">
      <p className="text-xs text-gray-400">{props.label}</p>
      <p className={`mt-1 text-lg font-semibold ${props.valueClass ?? "text-gray-200"}`}>{props.value}</p>
    </div>
  );
}

// ─── CSV (separador ; y coma decimal, para abrirlo en Excel en español) ───────

function num(v: number): string {
  return v.toFixed(2).replace(".", ",");
}

function downloadCsv(report: AnnualReport) {
  const header = [
    "Fecha venta", "ISIN", "Fondo", "Participaciones", "Primera adquisicion", "Ultima adquisicion",
    "Valor adquisicion", "Valor transmision", "Ganancia o perdida", "Computable", "Regla 2 meses",
  ];
  const rows = report.operations.map((op) => [
    op.saleDate, op.isin, nameOf(op.isin), String(op.quantityCovered).replace(".", ","),
    op.firstAcquisitionDate ?? "", op.lastAcquisitionDate ?? "",
    num(op.acquisitionValue), num(op.transmissionValue), num(op.gainLoss), num(op.computableGainLoss),
    op.twoMonthRuleViolation ? "Si" : "No",
  ]);
  const r = report.result;
  const totals = [
    [],
    ["Total valor adquisicion", num(report.totalAcquisitionValue)],
    ["Total valor transmision", num(report.totalTransmissionValue)],
    ["Ganancias computables", num(report.totalGains)],
    ["Perdidas computables", num(report.totalLosses)],
    ["Perdidas diferidas (regla 2 meses)", num(report.deferredLosses)],
    ["Saldo neto del ejercicio", num(r.netBalance)],
    ["Compensado de anos anteriores", num(r.carryForwardUsed)],
    ["Base del ahorro", num(r.taxableBase)],
    ["Cuota integra del ahorro", num(r.tax)],
  ];
  const escape = (c: string) => (/[;"\n]/.test(c) ? `"${c.replace(/"/g, '""')}"` : c);
  const csv = [header, ...rows, ...totals].map((row) => row.map(escape).join(";")).join("\r\n");

  // BOM para que Excel lea bien los acentos.
  const blob = new Blob(["﻿" + csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `resumen-fiscal-${report.portfolioId}-${report.fiscalYear}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}
