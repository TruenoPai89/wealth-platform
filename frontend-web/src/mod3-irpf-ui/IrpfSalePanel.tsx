"use client";

/**
 * IrpfSalePanel.tsx — Módulo 3: Simulador IRPF
 *
 * "¿Qué pasa con mi IRPF si vendo esto?": el cliente elige posición, cantidad y precio,
 * el backend Java calcula la cuota antes y después, y Claude lo explica en lenguaje natural.
 *
 * @module mod3-irpf-ui/IrpfSalePanel
 */

import React, { useState } from "react";
import { formatEur } from "@/core-ui/format";
import { explainSale, simulateSale } from "@/core-ui/services/irpfService";
import type { IrpfExplanation, IrpfSaleResponse } from "@/core-ui/types/irpf";

// Posiciones de la cartera demo (application.yml, wealth.rebalancer.targets.PF-001).
const POSITIONS = [
  { isin: "IE00B4L5Y983", name: "iShares Core MSCI World" },
  { isin: "IE00B3XXRP09", name: "Vanguard S&P 500" },
  { isin: "IE00B4WXJJ64", name: "iShares Core Euro Govt Bond" },
  { isin: "IE00B579F325", name: "Invesco Physical Gold" },
];

interface IrpfSalePanelProps {
  portfolioId?: string;
}

function NumberField(props: {
  label: string;
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <label className="flex flex-col gap-1 text-xs text-gray-400">
      {props.label}
      <input
        type="number"
        min="0"
        step="any"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
        className="rounded-md bg-surface-overlay border border-surface-border px-3 py-2 text-sm text-white"
      />
    </label>
  );
}

export function IrpfSalePanel({ portfolioId = "PF-001" }: IrpfSalePanelProps) {
  const [isin, setIsin] = useState(POSITIONS[0]!.isin);
  const [quantity, setQuantity] = useState("100");
  const [salePrice, setSalePrice] = useState("80");
  const [gains, setGains] = useState("10000");
  const [losses, setLosses] = useState("0");

  const [result, setResult] = useState<IrpfSaleResponse | null>(null);
  const [explanation, setExplanation] = useState<IrpfExplanation | null>(null);
  const [loading, setLoading] = useState(false);
  const [explaining, setExplaining] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSimulate() {
    setLoading(true);
    setError(null);
    setResult(null);
    setExplanation(null);
    try {
      const r = await simulateSale(portfolioId, {
        situation: {
          realizedGains: Number(gains) || 0,
          computableLosses: Number(losses) || 0,
        },
        isin,
        quantity: Number(quantity),
        salePrice: Number(salePrice),
      });
      setResult(r);
      // Las cifras se muestran ya; la explicación llega después (no bloquea).
      setExplaining(true);
      setExplanation(await explainSale(r));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
      setExplaining(false);
    }
  }

  const delta = result?.taxDelta ?? 0;
  const deltaClass =
    delta < 0 ? "text-semantic-gain" : delta > 0 ? "text-semantic-loss" : "text-gray-300";

  return (
    <section className="mt-8 rounded-xl border border-surface-border bg-surface-raised p-6">
      <h2 className="text-lg font-semibold text-white">Simulador IRPF de una venta</h2>
      <p className="mt-1 text-sm text-gray-400">
        Calcula cómo cambia tu cuota del ahorro si vendes ahora. Simulación orientativa.
      </p>

      <div className="mt-5 grid grid-cols-1 gap-4 md:grid-cols-5">
        <label className="flex flex-col gap-1 text-xs text-gray-400 md:col-span-2">
          Posición
          <select
            value={isin}
            onChange={(e) => setIsin(e.target.value)}
            className="rounded-md bg-surface-overlay border border-surface-border px-3 py-2 text-sm text-white"
          >
            {POSITIONS.map((p) => (
              <option key={p.isin} value={p.isin}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <NumberField label="Participaciones" value={quantity} onChange={setQuantity} />
        <NumberField label="Precio de venta (EUR)" value={salePrice} onChange={setSalePrice} />
        <div />
        <NumberField label="Plusvalías ya realizadas este año" value={gains} onChange={setGains} />
        <NumberField label="Minusvalías ya realizadas este año" value={losses} onChange={setLosses} />
      </div>

      <button
        onClick={handleSimulate}
        disabled={loading}
        className="mt-5 rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-500 disabled:opacity-50"
      >
        {loading ? "Calculando…" : "Simular venta"}
      </button>

      {error && <p className="mt-4 text-sm text-semantic-loss">{error}</p>}

      {result && (
        <div className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-4">
          <Kpi label="Resultado de la venta" value={formatEur(result.saleGainLoss)} />
          <Kpi label="Cuota sin vender" value={formatEur(result.before.tax)} />
          <Kpi label="Cuota si vendes" value={formatEur(result.after.tax)} />
          <Kpi
            label={delta < 0 ? "Ahorro fiscal" : "Coste fiscal"}
            value={formatEur(Math.abs(delta))}
            valueClass={deltaClass}
          />
        </div>
      )}

      {result?.warning && (
        <p className="mt-3 text-sm text-semantic-warning">{result.warning}</p>
      )}

      {explaining && (
        <p className="mt-4 text-sm text-gray-400 animate-pulse">Generando explicación…</p>
      )}

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

function Kpi(props: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="rounded-lg bg-surface-overlay p-4">
      <p className="text-xs text-gray-400">{props.label}</p>
      <p className={`mt-1 text-lg font-semibold ${props.valueClass ?? "text-white"}`}>
        {props.value}
      </p>
    </div>
  );
}
