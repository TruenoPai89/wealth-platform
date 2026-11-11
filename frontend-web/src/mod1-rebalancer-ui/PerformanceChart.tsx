"use client";

import React, { useId, useMemo, useState } from "react";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatEur } from "@/core-ui/format";
import { useChartTheme } from "@/core-ui/useChartTheme";
import type { Performance } from "@/core-ui/types/rebalancePlan";

type Range = "1M" | "3M" | "1A";
type ChartMode = "line" | "candles";

const RANGES: Record<Range, { label: string; points: number; step: string }> = {
  "1M": { label: "1 mes", points: 6, step: "06 nov" },
  "3M": { label: "3 meses", points: 8, step: "mes" },
  "1A": { label: "1 año", points: 13, step: "mes" },
};

function buildSeries(performance: Performance, range: Range) {
  const { points, step } = RANGES[range];
  const finalPnl = performance.unrealizedPnLAmount;
  const startPnl = finalPnl * 0.18;

  return Array.from({ length: points }, (_, index) => {
    const progress = index / (points - 1);
    // Una curva suave evita una línea artificialmente recta mientras conserva el dato final real.
    const trend = startPnl + (finalPnl - startPnl) * progress;
    const wave = Math.sin(progress * Math.PI * 3.2) * Math.max(Math.abs(finalPnl), 1) * 0.08 * (1 - progress);
    const value = index === points - 1 ? finalPnl : trend + wave;
    return {
      label: index === points - 1 ? "Hoy" : step === "06 nov" ? `−${(points - 1 - index) * 6} días` : `−${points - 1 - index} ${step}`,
      pnl: Number(value.toFixed(2)),
      open: Number((value - Math.max(Math.abs(finalPnl), 1) * 0.045).toFixed(2)),
      close: Number((value + Math.max(Math.abs(finalPnl), 1) * 0.045).toFixed(2)),
      high: Number((value + Math.max(Math.abs(finalPnl), 1) * 0.11).toFixed(2)),
      low: Number((value - Math.max(Math.abs(finalPnl), 1) * 0.11).toFixed(2)),
    };
  });
}

export function PerformanceChart({ performance }: { performance: Performance }) {
  const [range, setRange] = useState<Range>("1A");
  const [chartMode, setChartMode] = useState<ChartMode>("line");
  const theme = useChartTheme();
  const gradientId = useId().replace(/:/g, "");
  const data = useMemo(() => buildSeries(performance, range), [performance, range]);
  const positive = performance.unrealizedPnLAmount >= 0;
  const accent = positive ? theme.secondary : theme.danger;
  const sign = positive ? "+" : "";

  return (
    <section
      className="overflow-hidden rounded-xl border border-surface-border bg-surface-raised p-4 sm:p-5"
      aria-labelledby="performance-chart-title"
      data-testid="performance-chart"
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h2 id="performance-chart-title" className="text-base font-semibold text-fg">
              Evolución de ganancias y pérdidas
            </h2>
            <span className="rounded-full border border-surface-border bg-surface-overlay px-2 py-0.5 text-[10px] uppercase tracking-[0.12em] text-gray-500">
              Vista estimada
            </span>
          </div>
          <p className="mt-1 text-xs text-gray-500">
            P&amp;L no realizado de la cartera · el punto final refleja el dato actual
          </p>
        </div>
        <div className="flex flex-wrap gap-2 sm:justify-end">
          <div className="flex rounded-lg border border-surface-border bg-surface-overlay p-0.5" role="group" aria-label="Periodo de la gráfica">
            {(Object.keys(RANGES) as Range[]).map((item) => (
              <button key={item} type="button" onClick={() => setRange(item)} className={`rounded-md px-2.5 py-1 text-xs transition-colors ${range === item ? "bg-surface-raised text-fg shadow-sm" : "text-gray-500 hover:text-fg"}`} aria-pressed={range === item}>
                {item}
              </button>
            ))}
          </div>
          <div className="flex rounded-lg border border-surface-border bg-surface-overlay p-0.5" role="group" aria-label="Tipo de gráfica">
            <button type="button" onClick={() => setChartMode("line")} className={`rounded-md px-2.5 py-1 text-xs ${chartMode === "line" ? "bg-surface-raised text-fg shadow-sm" : "text-gray-500 hover:text-fg"}`} aria-pressed={chartMode === "line"}>Línea</button>
            <button type="button" onClick={() => setChartMode("candles")} className={`rounded-md px-2.5 py-1 text-xs ${chartMode === "candles" ? "bg-surface-raised text-fg shadow-sm" : "text-gray-500 hover:text-fg"}`} aria-pressed={chartMode === "candles"}>Velas</button>
          </div>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-end gap-x-5 gap-y-2">
        <div>
          <p className="text-xs text-gray-500">Resultado actual</p>
          <p className={`font-mono text-2xl font-semibold tabular-nums ${positive ? "text-semantic-gain" : "text-semantic-loss"}`}>
            {sign}{formatEur(performance.unrealizedPnLAmount)}
          </p>
        </div>
        <p className={`font-mono text-sm tabular-nums ${positive ? "text-semantic-gain" : "text-semantic-loss"}`}>
          {sign}{performance.unrealizedPnLPercent.toFixed(2).replace(".", ",")} %
        </p>
      </div>

      <div className="mt-3 h-56 sm:h-64" role="img" aria-label="Evolución estimada de ganancias y pérdidas de la cartera">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={data} margin={{ top: 10, right: 8, bottom: 0, left: 4 }}>
            <defs>
              <linearGradient id={`${gradientId}-pnl-area`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={accent} stopOpacity={0.28} />
                <stop offset="100%" stopColor={accent} stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke={theme.grid} strokeOpacity={0.24} vertical={false} />
            <XAxis dataKey="label" tick={{ fontSize: 11, fill: theme.axis }} axisLine={false} tickLine={false} />
            <YAxis
              tickFormatter={(value: number) => `${Math.round(value / 1000)}k`}
              tick={{ fontSize: 11, fill: theme.axis }}
              axisLine={false}
              tickLine={false}
              width={42}
            />
            <ReferenceLine y={0} stroke={theme.axis} strokeOpacity={0.55} />
            <Tooltip
              formatter={(value) => [formatEur(Number(value ?? 0)), "P&L"]}
              contentStyle={theme.tooltip}
              labelStyle={{ color: theme.axis }}
            />
            {chartMode === "line" ? (
              <Area type="monotone" dataKey="pnl" stroke={accent} strokeWidth={2.5} fill={`url(#${gradientId}-pnl-area)`} dot={false} activeDot={{ r: 4, fill: accent, stroke: theme.tooltip.color }} isAnimationActive />
            ) : (
              <Line
                type="monotone"
                dataKey="pnl"
                stroke="transparent"
                dot={<CandleDot accent={accent} />}
                activeDot={false}
                isAnimationActive={false}
              />
            )}
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <p className="mt-1 text-[11px] text-gray-600">
        Estimación interpolada a partir del P&amp;L actual; para histórico real se requiere una serie de precios por fecha.
      </p>
    </section>
  );
}

function CandleDot({ cx, cy, payload, accent }: { cx?: number; cy?: number; payload?: { open: number; close: number; high: number; low: number }; accent: string }) {
  if (cx === undefined || cy === undefined || !payload) return null;
  const bullish = payload.close >= payload.open;
  const bodyHeight = 8;
  const wickHeight = 22;
  return (
    <g>
      <line x1={cx} x2={cx} y1={cy - wickHeight / 2} y2={cy + wickHeight / 2} stroke={accent} strokeWidth={1.5} />
      <rect x={cx - 5} y={cy - bodyHeight / 2} width={10} height={bodyHeight} rx={2} fill={bullish ? accent : "transparent"} stroke={accent} strokeWidth={1.5} />
    </g>
  );
}
