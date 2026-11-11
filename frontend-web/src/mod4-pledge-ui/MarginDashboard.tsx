"use client";

/**
 * MarginDashboard.tsx — Métricas y gráficas de estrés del análisis de margin call.
 *
 *   · KPI: LTV actual, buffer de seguridad, distancia a margin call, garantía disponible vs requerida.
 *   · Gráfica 1: caída del colateral frente al umbral de ejecución (garantía mínima = deuda / umbral).
 *   · Gráfica 2: por fondo, peso en la garantía frente a su aportación al riesgo (volatilidad y correlaciones).
 *
 * Solo pinta lo que calcula el backend (POST /api/v1/margin/analyze); no recalcula nada.
 *
 * @module mod4-pledge-ui/MarginDashboard
 */

import React from "react";
import {
  Area,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatEur } from "@/core-ui/format";
import type { MarginRiskAnalysis } from "@/core-ui/types/margin";
import type { PledgeZone } from "@/core-ui/types/pledge";

const pct = (x: number, d = 1) => `${(x * 100).toFixed(d).replace(".", ",")} %`;

const ZONE: Record<PledgeZone, { label: string; text: string }> = {
  OK: { label: "Dentro del umbral", text: "text-semantic-gain" },
  LIMIT_EXCEEDED: { label: "Límite superado", text: "text-semantic-warning" },
  MARGIN_CALL: { label: "Margin call", text: "text-semantic-loss" },
};

// Colores fijos (legibles en tema claro y oscuro), como en AllocationCharts.
const C = { collateral: "#3b82f6", required: "#ef4444", weight: "#14b8a6", risk: "#f59e0b", grid: "#94a3b8" };

interface Props {
  analysis: MarginRiskAnalysis;
  /** true mientras se recalcula: atenúa los datos para no mostrar cifras viejas como actuales. */
  stale?: boolean;
}

export function MarginDashboard({ analysis, stale }: Props) {
  const m = analysis.metrics;
  const inCall = m.zone === "MARGIN_CALL";
  const style = ZONE[m.zone];

  // Buffer = distancia al margin call medida sobre la garantía (cuánto puede caer antes del aviso).
  const bufferEur = m.collateralSurplus;

  return (
    <section
      className={`flex flex-col gap-6 transition-opacity ${stale ? "opacity-60" : "opacity-100"}`}
      aria-label="Métricas de margin y colateral"
      aria-busy={stale}
      data-testid="margin-dashboard"
    >
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Kpi
          label="LTV actual"
          value={pct(m.ltv)}
          sub={`Umbral ${pct(m.marginCallLtv, 0)} · ${style.label}`}
          valueClass={style.text}
          testId="kpi-ltv"
        />
        <Kpi
          label="Buffer de seguridad"
          value={`${m.ltvHeadroom >= 0 ? "" : "−"}${pct(Math.abs(m.ltvHeadroom))}`}
          sub="Puntos de LTV hasta el umbral"
          valueClass={inCall ? "text-semantic-loss" : "text-fg"}
          testId="kpi-buffer"
        />
        <Kpi
          label="Distancia a Margin Call"
          value={inCall ? "Superado" : pct(m.distanceToMarginCall)}
          sub={
            inCall
              ? `Faltan ${formatEur(Math.abs(bufferEur))} de garantía`
              : `Caída de la garantía ≈ ${formatEur(bufferEur)}`
          }
          valueClass={inCall ? "text-semantic-loss" : "text-fg"}
          testId="kpi-distance"
        />
        <Kpi
          label="Colateral disponible vs requerido"
          value={formatEur(m.collateralValue)}
          sub={`Requerido ${formatEur(m.requiredCollateral)}`}
          valueClass={inCall ? "text-semantic-loss" : "text-fg"}
          testId="kpi-collateral"
        >
          <CoverageBar available={m.collateralValue} required={m.requiredCollateral} />
        </Kpi>
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <DrawdownChart analysis={analysis} />
        <FundBreakdownChart analysis={analysis} />
      </div>
    </section>
  );
}

// ─── Gráfica 1: caída del colateral vs umbral de ejecución ────────────────────

function DrawdownChart({ analysis }: { analysis: MarginRiskAnalysis }) {
  const required = analysis.metrics.requiredCollateral;
  const data = analysis.drawdownCurve.map((p) => ({
    drop: Math.round(p.drop * 100),
    collateral: p.collateralValue,
    required,
    ltv: p.ltv,
  }));
  const trigger = analysis.metrics.distanceToMarginCall;

  return (
    <div className="rounded-xl border border-surface-border bg-surface-raised p-5" data-testid="drawdown-chart">
      <h3 className="text-sm font-semibold text-fg">Simulación de caída de mercado</h3>
      <p className="mt-0.5 text-xs text-gray-500">
        Valor de la garantía si todos los fondos aportados caen, frente a la garantía mínima (umbral de ejecución).
      </p>

      <div className="mt-3 h-64" role="img" aria-label="Valor de la garantía según la caída de mercado frente al umbral de margin call">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={data} margin={{ top: 22, right: 12, bottom: 4, left: 4 }}>
            <CartesianGrid stroke={C.grid} strokeOpacity={0.25} vertical={false} />
            <XAxis
              dataKey="drop"
              type="number"
              domain={[0, 60]}
              ticks={[0, 10, 20, 30, 40, 50, 60]}
              tickFormatter={(v: number) => (v === 0 ? "0 %" : `−${v} %`)}
              tick={{ fontSize: 11, fill: C.grid }}
              stroke={C.grid}
            />
            <YAxis
              tickFormatter={(v: number) => `${Math.round(v / 1000)}k`}
              tick={{ fontSize: 11, fill: C.grid }}
              stroke={C.grid}
              width={44}
            />
            <Tooltip
              formatter={(value, name) => [formatEur(Number(value ?? 0)), String(name ?? "")]}
              labelFormatter={(l) => `Caída de −${String(l)} %`}
              contentStyle={{ background: "rgb(var(--surface-raised))", border: "1px solid rgb(var(--surface-border))", borderRadius: 8, fontSize: 12 }}
            />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            <Area
              type="monotone"
              dataKey="collateral"
              name="Garantía computable"
              stroke={C.collateral}
              fill={C.collateral}
              fillOpacity={0.18}
              strokeWidth={2}
              isAnimationActive={false}
            />
            <Line
              type="linear"
              dataKey="required"
              name="Garantía mínima (margin call)"
              stroke={C.required}
              strokeWidth={2}
              strokeDasharray="6 4"
              dot={false}
              isAnimationActive={false}
            />
            {[10, 20, 30].map((d) => (
              <ReferenceLine key={d} x={d} stroke={C.grid} strokeDasharray="2 4" label={{ value: `−${d} %`, fontSize: 10, fill: C.grid, position: "top" }} />
            ))}
            {trigger > 0 && trigger * 100 <= 60 && (
              <ReferenceLine
                x={trigger * 100}
                stroke={C.required}
                strokeOpacity={0.7}
                label={{ value: `Margin call ≈ −${(trigger * 100).toFixed(0)} %`, fontSize: 10, fill: C.required, position: "insideTopRight" }}
              />
            )}
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

// ─── Gráfica 2: peso vs contribución al riesgo por fondo ──────────────────────

function FundBreakdownChart({ analysis }: { analysis: MarginRiskAnalysis }) {
  const data = analysis.funds.map((f) => ({
    name: f.name,
    weight: +(f.weight * 100).toFixed(1),
    risk: +(f.riskContribution * 100).toFixed(1),
  }));

  return (
    <div className="rounded-xl border border-surface-border bg-surface-raised p-5" data-testid="fund-breakdown">
      <h3 className="text-sm font-semibold text-fg">Volatilidad e impacto por fondo</h3>
      <p className="mt-0.5 text-xs text-gray-500">
        Un fondo volátil pesa más en el riesgo que en la garantía. Volatilidad de la garantía:{" "}
        <strong className="text-fg">{pct(analysis.metrics.collateralVolatility)}</strong> anual.
      </p>

      <div className="mt-3 h-44" role="img" aria-label="Peso en la garantía frente a contribución al riesgo de cada fondo">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, bottom: 4, left: 8 }}>
            <CartesianGrid stroke={C.grid} strokeOpacity={0.25} horizontal={false} />
            <XAxis type="number" domain={[0, 100]} unit=" %" tick={{ fontSize: 11, fill: C.grid }} stroke={C.grid} />
            <YAxis type="category" dataKey="name" width={130} tick={{ fontSize: 11, fill: C.grid }} stroke={C.grid} />
            <Tooltip
              formatter={(v, n) => [`${Number(v ?? 0).toFixed(1).replace(".", ",")} %`, String(n ?? "")]}
              contentStyle={{ background: "rgb(var(--surface-raised))", border: "1px solid rgb(var(--surface-border))", borderRadius: 8, fontSize: 12 }}
            />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            <Bar dataKey="weight" name="Peso en la garantía" fill={C.weight} isAnimationActive={false} />
            <Bar dataKey="risk" name="Contribución al riesgo" fill={C.risk} isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="mt-3 overflow-x-auto">
        <table className="w-full text-xs" aria-label="Impacto individual de cada fondo en garantía">
          <thead>
            <tr className="text-left text-gray-500">
              <th className="py-1 pr-3 font-normal">Fondo</th>
              <th className="py-1 pr-3 text-right font-normal">Aportado</th>
              <th className="py-1 pr-3 text-right font-normal">Vol. anual</th>
              <th className="py-1 pr-3 text-right font-normal" title="Pérdida de garantía computable en un movimiento adverso de 1 desviación típica anual">
                Pérdida 1σ
              </th>
              <th className="py-1 text-right font-normal" title="Caída de este fondo, él solo, que dispara el margin call">
                Caída que dispara MC
              </th>
            </tr>
          </thead>
          <tbody className="text-gray-300">
            {analysis.funds.map((f) => (
              <tr key={f.instrumentId} className="border-t border-surface-border/60">
                <td className="py-1.5 pr-3">{f.name}</td>
                <td className="py-1.5 pr-3 text-right font-mono">{formatEur(f.pledgedAmount)}</td>
                <td className="py-1.5 pr-3 text-right font-mono">{pct(f.annualVolatility)}</td>
                <td className="py-1.5 pr-3 text-right font-mono">{formatEur(f.oneSigmaLoss)}</td>
                <td className="py-1.5 text-right font-mono">
                  {f.standaloneDropToMarginCall === null ? "ni al 100 %" : pct(f.standaloneDropToMarginCall)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ─── Piezas ───────────────────────────────────────────────────────────────────

function Kpi(props: {
  label: string;
  value: string;
  sub?: string;
  valueClass?: string;
  testId?: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-surface-border bg-surface-raised p-4" data-testid={props.testId}>
      <p className="text-xs text-gray-400">{props.label}</p>
      <p className={`mt-1 text-2xl font-semibold ${props.valueClass ?? "text-fg"}`}>{props.value}</p>
      {props.sub && <p className="mt-0.5 text-xs text-gray-500">{props.sub}</p>}
      {props.children}
    </div>
  );
}

/** Barra: garantía disponible frente a la requerida (la marca roja es la requerida). */
function CoverageBar({ available, required }: { available: number; required: number }) {
  const max = Math.max(available, required) * 1.05 || 1;
  const ok = available >= required;
  return (
    <div className="relative mt-2 h-2 overflow-hidden rounded-full bg-surface-overlay" aria-hidden="true">
      <div
        className={`h-full rounded-full transition-all duration-300 ${ok ? "bg-semantic-gain" : "bg-semantic-loss"}`}
        style={{ width: `${(available / max) * 100}%` }}
      />
      <div className="absolute bottom-0 top-0 w-0.5 bg-semantic-loss" style={{ left: `${(required / max) * 100}%` }} />
    </div>
  );
}
