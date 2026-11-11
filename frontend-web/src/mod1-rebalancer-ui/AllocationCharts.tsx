"use client";

/**
 * AllocationCharts.tsx — Gráficos de distribución de la cartera (SVG puro, sin dependencias).
 *
 *   1. Por clase de activo: Renta Variable / Renta Fija / Materias Primas y Alternativos / Efectivo.
 *   2. Por fondo: un anillo con el peso ACTUAL (exterior) y otro con el peso OBJETIVO (interior).
 *
 * Reciben los pesos ya calculados por el backend (0–1); aquí solo se agrupan para pintar. Siguen la vista de la
 * tabla: en «Cartera Proyectada» el anillo exterior es el peso tras ejecutar las órdenes.
 * Los colores de las porciones son fijos (legibles en tema claro y oscuro); el resto usa los tokens del tema.
 *
 * @module mod1-rebalancer-ui/AllocationCharts
 */

import React, { useState } from "react";
import type { AssetClass } from "@/core-ui/types/portfolio";
import { formatWeight } from "@/core-ui/format";

export interface ChartPosition {
  instrumentId: string;
  name: string;
  assetClass: AssetClass;
  /** Peso en la vista mostrada (actual, o proyectado tras las órdenes). 0–1. */
  currentWeight: number;
  targetWeight: number;
}

interface Slice {
  key: string;
  label: string;
  value: number;
  color: string;
}

// ─── Clases de activo ─────────────────────────────────────────────────────────

type ClassGroup = "EQUITY" | "FIXED_INCOME" | "ALTERNATIVE" | "CASH";

const GROUPS: { id: ClassGroup; label: string; color: string }[] = [
  { id: "EQUITY", label: "Renta Variable", color: "#3b82f6" },
  { id: "FIXED_INCOME", label: "Renta Fija", color: "#14b8a6" },
  { id: "ALTERNATIVE", label: "Materias Primas / Alternativos", color: "#f59e0b" },
  { id: "CASH", label: "Efectivo", color: "#94a3b8" },
];

function groupOf(assetClass: AssetClass): ClassGroup {
  if (assetClass === "EQUITY" || assetClass === "FIXED_INCOME" || assetClass === "CASH") return assetClass;
  return "ALTERNATIVE"; // COMMODITY, ALTERNATIVE y REAL_ESTATE
}

const FUND_COLORS = ["#3b82f6", "#14b8a6", "#f59e0b", "#a855f7", "#ec4899", "#22c55e", "#f97316", "#06b6d4"];

// ─── Donut ────────────────────────────────────────────────────────────────────

const VIEWBOX = 42;
const CENTER = VIEWBOX / 2;

interface Ring {
  radius: number;
  stroke: number;
  slices: Slice[];
  label: string;
}

/** Cada porción es un círculo con trazo discontinuo; pathLength=100 convierte el peso directamente en longitud. */
function Donut({ rings, centerTop, centerBottom, ariaLabel }: {
  rings: Ring[];
  centerTop: string;
  centerBottom: string;
  ariaLabel: string;
}) {
  const [hovered, setHovered] = useState<{ label: string; value: number; ring: string } | null>(null);
  return (
    <div className="relative w-48 h-48 shrink-0">
    <svg viewBox={`0 0 ${VIEWBOX} ${VIEWBOX}`} className="w-48 h-48" role="img" aria-label={ariaLabel}>
      {rings.map((ring) => {
        const total = ring.slices.reduce((s, x) => s + Math.max(x.value, 0), 0);
        let offset = 0;
        return (
          <g key={ring.label} transform={`rotate(-90 ${CENTER} ${CENTER})`} data-testid={`ring-${ring.label}`}>
            {/* Pista de fondo: se ve si la cartera no suma (p. ej. sin datos) */}
            <circle cx={CENTER} cy={CENTER} r={ring.radius} fill="none" stroke="rgb(var(--surface-overlay))" strokeWidth={ring.stroke} />
            {total > 0 &&
              ring.slices
                .filter((s) => s.value > 0)
                .map((s) => {
                  const pct = (s.value / total) * 100;
                  const circle = (
                    <circle
                      key={s.key}
                      cx={CENTER}
                      cy={CENTER}
                      r={ring.radius}
                      fill="none"
                      stroke={s.color}
                      strokeWidth={ring.stroke}
                      pathLength={100}
                      strokeDasharray={`${pct} ${100 - pct}`}
                      strokeDashoffset={-offset}
                      className="cursor-help transition-opacity hover:opacity-75"
                      onMouseEnter={() => setHovered({ label: s.label, value: s.value, ring: ring.label.includes("objetivo") ? "Objetivo" : "Actual" })}
                      onMouseLeave={() => setHovered(null)}
                    >
                      <title>{`${s.label}: ${formatWeight(s.value)}`}</title>
                    </circle>
                  );
                  offset += pct;
                  return circle;
                })}
          </g>
        );
      })}
      <text x={CENTER} y={CENTER - 0.4} textAnchor="middle" className="fill-current text-fg" style={{ fontSize: 2.9, fontWeight: 600 }}>
        {centerTop}
      </text>
      <text x={CENTER} y={CENTER + 3} textAnchor="middle" className="fill-current text-gray-500" style={{ fontSize: 2.1 }}>
        {centerBottom}
      </text>
    </svg>
    {hovered && (
      <div className="pointer-events-none absolute left-1/2 top-1/2 z-10 w-36 -translate-x-1/2 -translate-y-1/2 rounded-lg border border-surface-border bg-surface-raised/95 px-3 py-2 text-center shadow-xl backdrop-blur" role="status">
        <p className="truncate text-xs font-semibold text-fg">{hovered.label}</p>
        <p className="mt-0.5 text-[10px] uppercase tracking-[0.12em] text-gray-500">{hovered.ring}</p>
        <p className="mt-1 font-mono text-sm text-brand-300">{formatWeight(hovered.value)}</p>
      </div>
    )}
    </div>
  );
}

// ─── Leyenda con actual vs objetivo ───────────────────────────────────────────

function Legend({ rows }: { rows: { key: string; label: string; color: string; current: number; target: number }[] }) {
  return (
    <table className="text-sm w-full table-auto" aria-label="Leyenda del gráfico">
      <thead>
        <tr className="text-xs text-gray-500 uppercase tracking-wider">
          <th className="text-left font-semibold pb-1.5 whitespace-nowrap">Activo</th>
          <th className="text-right font-semibold pb-1.5 pl-3 whitespace-nowrap">Actual</th>
          <th className="text-right font-semibold pb-1.5 pl-3 whitespace-nowrap">Objetivo</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => {
          const gap = r.current - r.target;
          return (
            <tr key={r.key} data-testid={`legend-${r.key}`}>
              <td className="py-1 pr-2 w-full max-w-0">
                <span className="inline-flex items-center gap-2 min-w-0 max-w-full">
                  <span className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ backgroundColor: r.color }} aria-hidden="true" />
                  <span className="text-fg truncate" title={`${r.label}: actual ${formatWeight(r.current)}, objetivo ${formatWeight(r.target)}`}>{r.label}</span>
                </span>
              </td>
              <td className="py-1 pl-3 text-right font-mono text-fg whitespace-nowrap">{formatWeight(r.current)}</td>
              <td className="py-1 pl-3 text-right font-mono whitespace-nowrap">
                <span className="text-gray-500">{formatWeight(r.target)}</span>
                {Math.abs(gap) >= 0.005 && (
                  <span className={`ml-2 text-xs ${gap > 0 ? "text-semantic-warning" : "text-blue-400"}`}>
                    {gap > 0 ? "▲" : "▼"}
                  </span>
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function ChartCard({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
  return (
    <section className="bg-surface-raised border border-surface-border rounded-xl p-5 flex flex-col gap-4" aria-label={title}>
      <div>
        <h3 className="text-base font-semibold text-fg">{title}</h3>
        <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>
      </div>
      {/* flex-wrap: si no cabe el anillo y la leyenda en una fila, la leyenda baja debajo (nunca se corta) */}
      <div className="flex items-center gap-5 flex-wrap">{children}</div>
    </section>
  );
}

// ─── Componente principal ─────────────────────────────────────────────────────

export function AllocationCharts({ positions, viewLabel }: { positions: ChartPosition[]; viewLabel: string }) {
  if (positions.length === 0) return null;

  // 1) Por clase de activo: se agregan los pesos de cada grupo (actual y objetivo).
  const byClass = GROUPS.map((g) => {
    const members = positions.filter((p) => groupOf(p.assetClass) === g.id);
    return {
      key: g.id,
      label: g.label,
      color: g.color,
      current: members.reduce((s, p) => s + p.currentWeight, 0),
      target: members.reduce((s, p) => s + p.targetWeight, 0),
    };
  }).filter((g) => g.current > 0 || g.target > 0);

  // 2) Por fondo: el efectivo también es una porción (es un peso objetivo más).
  const byFund = positions.map((p, i) => ({
    key: p.instrumentId,
    label: p.name,
    color: p.assetClass === "CASH" ? "#94a3b8" : FUND_COLORS[i % FUND_COLORS.length] ?? "#64748b",
    current: p.currentWeight,
    target: p.targetWeight,
  }));

  const toSlices = (rows: typeof byFund, field: "current" | "target"): Slice[] =>
    rows.map((r) => ({ key: r.key, label: r.label, value: r[field], color: r.color }));

  return (
    <div className="grid gap-4 xl:grid-cols-2" data-testid="allocation-charts">
      <ChartCard title="Distribución por clase de activo" subtitle={`${viewLabel} · exterior: actual · interior: objetivo`}>
        <Donut
          ariaLabel="Distribución por clase de activo, actual y objetivo"
          centerTop={`${byClass.length} clases`}
          centerBottom="actual / objetivo"
          rings={[
            { label: "clase-actual", radius: 17.4, stroke: 3.4, slices: toSlices(byClass, "current") },
            { label: "clase-objetivo", radius: 13.4, stroke: 3.4, slices: toSlices(byClass, "target") },
          ]}
        />
        <div className="flex-1 min-w-0 basis-64 overflow-x-auto">
          <Legend rows={byClass} />
        </div>
      </ChartCard>

      <ChartCard title="Distribución por fondos" subtitle={`${viewLabel} · exterior: actual · interior: objetivo`}>
        <Donut
          ariaLabel="Peso de cada fondo, actual y objetivo"
          centerTop={`${byFund.length} posiciones`}
          centerBottom="actual / objetivo"
          rings={[
            { label: "fondo-actual", radius: 17.4, stroke: 3.4, slices: toSlices(byFund, "current") },
            { label: "fondo-objetivo", radius: 13.4, stroke: 3.4, slices: toSlices(byFund, "target") },
          ]}
        />
        <div className="flex-1 min-w-0 basis-64 overflow-x-auto">
          <Legend rows={byFund} />
        </div>
      </ChartCard>
    </div>
  );
}
