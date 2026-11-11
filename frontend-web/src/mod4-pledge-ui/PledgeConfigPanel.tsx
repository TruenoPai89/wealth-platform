"use client";

/**
 * PledgeConfigPanel.tsx — Controles interactivos de la pignoración (sin contrato en PDF).
 *
 *   · Multiselector de fondos de la cartera, con el importe (EUR) aportado de cada uno y ajustes rápidos por %.
 *   · Parámetros del crédito: importe, tipo de interés, plazo y umbral de margin call (LTV máximo).
 *
 * Es un componente controlado: no calcula nada, solo edita el estado; el análisis lo hace el backend.
 *
 * @module mod4-pledge-ui/PledgeConfigPanel
 */

import React from "react";
import { formatEur } from "@/core-ui/format";
import { SliderField } from "@/mod4-pledge-ui/SliderField";
import {
  loanSliderMax,
  type FundOption,
  type PledgeConfigState,
} from "@/mod4-pledge-ui/pledgeConfig";

const CLASS_LABEL: Record<FundOption["assetClass"], string> = {
  EQUITY: "Renta variable",
  FIXED_INCOME: "Renta fija",
  COMMODITY: "Materias primas",
  CASH: "Efectivo",
};

interface Props {
  funds: FundOption[];
  config: PledgeConfigState;
  onChange: (next: PledgeConfigState) => void;
  disabled?: boolean;
}

export function PledgeConfigPanel({ funds, config, onChange, disabled }: Props) {
  const set = (patch: Partial<PledgeConfigState>) => onChange({ ...config, ...patch });

  function toggleFund(fund: FundOption) {
    const next = { ...config.collateral };
    if (fund.instrumentId in next) delete next[fund.instrumentId];
    else next[fund.instrumentId] = fund.marketValue;
    set({ collateral: next });
  }

  function setAmount(fund: FundOption, amount: number) {
    set({ collateral: { ...config.collateral, [fund.instrumentId]: amount } });
  }

  const pledged = Object.values(config.collateral).reduce((a, b) => a + b, 0);
  const loanMax = loanSliderMax(config);

  return (
    <section
      className="rounded-xl border border-surface-border bg-surface-raised p-4 sm:p-6"
      aria-labelledby="pledge-config-title"
      data-testid="pledge-config-panel"
    >
      <h2 id="pledge-config-title" className="text-lg font-semibold text-fg">
        Configura tu pignoración
      </h2>
      <p className="mt-1 text-sm text-gray-400">
        Elige qué fondos aportas como garantía y define el crédito. El análisis se recalcula al instante.
      </p>

      <div className="mt-5 grid grid-cols-1 gap-6 sm:gap-8 xl:grid-cols-2">
        {/* ── Fondos en garantía ── */}
        <fieldset className="flex min-w-0 flex-col gap-3" disabled={disabled}>
          <legend className="text-sm font-semibold text-fg">Fondos en garantía</legend>

          {funds.length === 0 && (
            <div className="flex flex-col gap-3" role="status" aria-label="Cargando fondos de la cartera">
              {[0, 1, 2].map((i) => (
                <div key={i} className="skeleton h-16 w-full" aria-hidden="true" />
              ))}
            </div>
          )}

          {funds.map((f) => {
            const amount = config.collateral[f.instrumentId];
            const selected = amount !== undefined;
            return (
              <div
                key={f.instrumentId}
                className={[
                  "rounded-lg border p-3 transition-colors hover:border-gray-500",
                  selected ? "border-brand-500/50 bg-brand-500/5" : "border-surface-border",
                ].join(" ")}
                data-testid={`fund-option-${f.instrumentId}`}
              >
                <label className="flex cursor-pointer items-start gap-3">
                  <input
                    type="checkbox"
                    checked={selected}
                    onChange={() => toggleFund(f)}
                    className="mt-1 h-4 w-4 accent-brand-500"
                  />
                  <span className="flex-1">
                    <span className="block text-sm font-medium text-fg">{f.name}</span>
                    <span className="block text-xs text-gray-500">
                      {CLASS_LABEL[f.assetClass]} · posición {formatEur(f.marketValue)}
                    </span>
                  </span>
                </label>

                {selected && (
                  <div className="mt-3 sm:pl-7">
                    <SliderField
                      label="Importe aportado"
                      value={amount}
                      onChange={(v) => setAmount(f, v)}
                      min={0}
                      max={f.marketValue}
                      step={Math.max(50, Math.round(f.marketValue / 200 / 50) * 50)}
                      unit="€"
                      decimals={0}
                      hint={`${((amount / f.marketValue) * 100).toFixed(0)} % de la posición`}
                      quick={[25, 50, 75, 100].map((p) => ({
                        label: `${p} %`,
                        set: Math.round(((f.marketValue * p) / 100) * 100) / 100,
                      }))}
                    />
                  </div>
                )}
              </div>
            );
          })}

          <p className="text-xs text-gray-500" role="status">
            {Object.keys(config.collateral).length === 0
              ? "Selecciona al menos un fondo."
              : `Total aportado: ${formatEur(pledged)} (antes de descuentos).`}
          </p>
        </fieldset>

        {/* ── Parámetros del crédito ── */}
        <fieldset className="flex min-w-0 flex-col gap-5" disabled={disabled}>
          <legend className="mb-3 text-sm font-semibold text-fg">Parámetros del crédito</legend>

          <SliderField
            label="Importe dispuesto"
            value={config.loanAmount}
            onChange={(v) => set({ loanAmount: v })}
            min={0}
            max={loanMax}
            step={500}
            unit="€"
            quick={[
              { label: "−5.000", delta: -5000 },
              { label: "−1.000", delta: -1000 },
              { label: "+1.000", delta: 1000 },
              { label: "+5.000", delta: 5000 },
            ]}
          />

          <SliderField
            label="Tipo de interés anual"
            value={config.interestRate}
            onChange={(v) => set({ interestRate: v })}
            min={0}
            max={0.15}
            step={0.0025}
            scale={100}
            decimals={2}
            unit="%"
            quick={[
              { label: "3 %", set: 0.03 },
              { label: "4 %", set: 0.04 },
              { label: "5 %", set: 0.05 },
              { label: "6 %", set: 0.06 },
            ]}
          />

          <SliderField
            label="Plazo"
            value={config.termYears}
            onChange={(v) => set({ termYears: Math.round(v) })}
            min={1}
            max={30}
            step={1}
            unit="años"
            quick={[
              { label: "3", set: 3 },
              { label: "5", set: 5 },
              { label: "10", set: 10 },
              { label: "15", set: 15 },
            ]}
          />

          <SliderField
            label="Umbral de Margin Call (LTV máximo)"
            value={config.marginCallLtv}
            onChange={(v) => set({ marginCallLtv: v })}
            min={0.3}
            max={0.95}
            step={0.01}
            scale={100}
            decimals={0}
            unit="%"
            hint="LTV = deuda / valor de la garantía"
            quick={[
              { label: "60 %", set: 0.6 },
              { label: "70 %", set: 0.7 },
              { label: "80 %", set: 0.8 },
            ]}
          />
        </fieldset>
      </div>
    </section>
  );
}
