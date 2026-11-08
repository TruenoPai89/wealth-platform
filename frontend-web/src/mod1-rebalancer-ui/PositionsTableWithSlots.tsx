"use client";

/**
 * PositionsTableWithSlots.tsx — Tabla maestra de posiciones del portfolio
 *
 * Consume el contrato de Raúl (RebalancePlan):
 *   contracts/mocks-contracts/rebalancer_base_mod1.json
 *
 * SLOTS reservados para módulos futuros (NO importar nada de mod2/3/4 aquí):
 *   "StatusBadgeSlot"  → badge por fila  (Mod2: alerta 2M, Mod4: candado LTV)
 *   "ActionRowSlot"    → fila extra       (Mod4: LtvHealthBar)
 *   "HeaderKpiSlot"    → KPIs cabecera   (Mod2: ahorro fiscal potencial)
 *
 * @module mod1-rebalancer-ui/PositionsTableWithSlots
 */

import React from "react";
import type {
  AssetPosition,
  AssetClass,
  RebalancePlan,
  CashFlowSummary,
} from "@/core-ui/types/portfolio";
import { formatEur, formatUnits } from "@/core-ui/format";
import { Slot } from "@/core-ui/SlotRegistry";
import type { SlotRowProps, SlotHeaderProps } from "@/core-ui/SlotRegistry";

// ─── Props ────────────────────────────────────────────────────────────────────

export interface PositionsTableProps {
  plan: RebalancePlan | null;
  isLoading?: boolean;
  error?: string | null;
}

// ─── Helpers de formateo ──────────────────────────────────────────────────────

const PCT_FMT = new Intl.NumberFormat("es-ES", {
  style: "percent",
  minimumFractionDigits: 2, maximumFractionDigits: 2,
});

// Importes con separador de miles también en 4 cifras (9.500,00 €): ver core-ui/format.ts
function fmtEur(v: number) { return formatEur(v); }
function fmtPct(v: number) { return PCT_FMT.format(v); }  // v es 0–1
function fmtDelta(v: number) {
  const sign = v >= 0 ? "+" : "";
  return `${sign}${(v * 100).toFixed(2).replace(".", ",")} pp`;
}
function fmtPnlPct(v: number) {
  const sign = v >= 0 ? "+" : "";
  return `${sign}${v.toFixed(2).replace(".", ",")} %`;
}

// ─── Colores por clase de activo ──────────────────────────────────────────────

const ASSET_CLASS_STYLES: Record<AssetClass, { bg: string; text: string; label: string }> = {
  EQUITY:       { bg: "bg-blue-500/15",   text: "text-blue-400",   label: "Renta Variable" },
  FIXED_INCOME: { bg: "bg-teal-500/15",   text: "text-teal-400",   label: "Renta Fija" },
  COMMODITY:    { bg: "bg-yellow-500/15", text: "text-yellow-400", label: "Materia Prima" },
  CASH:         { bg: "bg-gray-500/15",   text: "text-gray-400",   label: "Efectivo" },
  ALTERNATIVE:  { bg: "bg-orange-500/15", text: "text-orange-400", label: "Alternativo" },
  REAL_ESTATE:  { bg: "bg-purple-500/15", text: "text-purple-400", label: "Inmobiliario" },
};

// ─── Sub-componentes ──────────────────────────────────────────────────────────

function AssetClassBadge({ assetClass }: { assetClass: AssetClass }) {
  const s = ASSET_CLASS_STYLES[assetClass] ?? ASSET_CLASS_STYLES.ALTERNATIVE;
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${s.bg} ${s.text}`}>
      {s.label}
    </span>
  );
}

function WeightBar({ current, target }: { current: number; target: number }) {
  const deviation = current - target;
  const isOver  = deviation >  0.005;
  const isUnder = deviation < -0.005;
  const barColor = isOver ? "bg-semantic-warning" : isUnder ? "bg-blue-500" : "bg-semantic-gain";
  const textColor = isOver ? "text-semantic-warning" : isUnder ? "text-blue-400" : "text-semantic-gain";

  return (
    <div className="flex flex-col gap-1 min-w-[120px]">
      <div className="flex justify-between text-xs">
        <span className={textColor}>{fmtPct(current)}</span>
        <span className="text-gray-500">obj. {fmtPct(target)}</span>
      </div>
      <div className="relative h-1.5 bg-surface-overlay rounded-full overflow-hidden w-full">
        <div
          className="absolute top-0 bottom-0 w-0.5 bg-gray-500 z-10"
          style={{ left: `${Math.min(target * 100, 100)}%` }}
        />
        <div
          className={`h-full rounded-full transition-all duration-300 ${barColor}`}
          style={{ width: `${Math.min(current * 100, 100)}%` }}
        />
      </div>
    </div>
  );
}

function DeltaBadge({ delta }: { delta: number }) {
  if (Math.abs(delta) < 0.001) {
    return <span className="text-xs text-gray-600">Sin cambio</span>;
  }
  const isPositive = delta > 0;
  return (
    <span className={`text-xs font-medium ${isPositive ? "text-semantic-gain" : "text-semantic-loss"}`}>
      {fmtDelta(delta)}
    </span>
  );
}

function OrderBadge({ position }: { position: AssetPosition }) {
  if (!position.order) return null;
  const isBuy = position.order.side === "BUY";
  return (
    <span className={[
      "inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-semibold",
      isBuy
        ? "bg-semantic-gain/15 text-semantic-gain border border-semantic-gain/30"
        : "bg-semantic-loss/15 text-semantic-loss border border-semantic-loss/30",
    ].join(" ")}>
      {isBuy ? "▲" : "▼"} {isBuy ? "COMPRAR" : "VENDER"}{" "}
      {formatUnits(position.order.quantity)} uds.
    </span>
  );
}

function PnlCell({ position }: { position: AssetPosition }) {
  // Schema 1.1: el backend calcula coste base y P&L por posición. El efectivo no tiene P&L (se pinta "—").
  if (position.unrealizedPnLAmount !== undefined) {
    const amount = position.unrealizedPnLAmount;
    const pct = position.unrealizedPnLPercent ?? 0;
    const color = amount >= 0 ? "text-semantic-gain" : "text-semantic-loss";
    return (
      <div className="flex flex-col items-end">
        <span className={`text-sm font-medium ${color}`}>
          {amount === 0 ? "—" : fmtEur(amount)}
        </span>
        {pct !== 0 && (
          <span className={`text-xs ${color} opacity-80`}>{fmtPnlPct(pct)}</span>
        )}
      </div>
    );
  }
  if (!position.pnl) {
    // Sin datos de P&L del backend base — mostrar valor deltaValue como referencia
    const delta = position.deltaValue;
    const color = delta >= 0 ? "text-semantic-gain" : "text-semantic-loss";
    return (
      <div className="flex flex-col items-end">
        <span className={`text-xs ${color}`}>{fmtEur(Math.abs(delta))}</span>
        <span className="text-xs text-gray-600">{delta >= 0 ? "a comprar" : "a vender"}</span>
      </div>
    );
  }
  const { unrealized, unrealizedPct } = position.pnl;
  const color = unrealized >= 0 ? "text-semantic-gain" : "text-semantic-loss";
  return (
    <div className="flex flex-col items-end">
      <span className={`text-sm font-medium ${color}`}>
        {unrealized === 0 ? "—" : fmtEur(unrealized)}
      </span>
      {unrealizedPct !== 0 && (
        <span className={`text-xs ${color} opacity-80`}>{fmtPnlPct(unrealizedPct)}</span>
      )}
    </div>
  );
}

// ─── Fila de posición ─────────────────────────────────────────────────────────

function PositionRow({ position, isEven }: { position: AssetPosition; isEven: boolean }) {
  const slotProps: SlotRowProps = { position };
  const displayId = position.ticker ?? position.isin ?? position.instrumentId;

  return (
    <>
      <tr className={[
        "border-b border-surface-border hover:bg-surface-overlay/50 transition-colors",
        isEven ? "bg-surface-base/30" : "",
      ].join(" ")}>
        {/* Activo */}
        <td className="px-4 py-3">
          <div className="flex flex-col gap-1">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-mono text-sm text-fg font-semibold">{displayId}</span>
              <AssetClassBadge assetClass={position.assetClass} />
              {/* ── SLOT: StatusBadgeSlot ───────────────────────────────────
                  Módulo 2 → TwoMonthWarningBadge
                  Módulo 4 → PledgedLockIcon
                  Si nadie registra nada → null silencioso
              ─────────────────────────────────────────────────────────── */}
              <Slot<SlotRowProps> name="StatusBadgeSlot" slotProps={slotProps} />
            </div>
            <span className="text-xs text-gray-400 truncate max-w-xs" title={position.name}>
              {position.name}
            </span>
            {position.isin && (
              <span className="text-xs text-gray-600 font-mono">{position.isin}</span>
            )}
          </div>
        </td>

        {/* Peso actual / objetivo / delta */}
        <td className="px-4 py-3">
          <div className="flex flex-col gap-1.5">
            <WeightBar current={position.currentWeight} target={position.targetWeight} />
            <DeltaBadge delta={position.deltaWeight} />
          </div>
        </td>

        {/* Valor mercado + orden propuesta */}
        <td className="px-4 py-3">
          <div className="flex flex-col gap-1 items-end">
            <span className="text-sm text-fg font-medium font-mono">
              {fmtEur(position.marketValue)}
            </span>
            <span className="text-xs text-gray-500">
              {formatUnits(position.quantity)} uds.
            </span>
            <OrderBadge position={position} />
          </div>
        </td>

        {/* P&L / movimiento */}
        <td className="px-4 py-3">
          <PnlCell position={position} />
        </td>
      </tr>

      {/* ── SLOT: ActionRowSlot ────────────────────────────────────────────
          Módulo 4 → LtvHealthBar (barra de salud del préstamo lombardo)
          Si nadie registra nada → esta celda queda vacía y es invisible
      ─────────────────────────────────────────────────────────────────── */}
      <tr className="border-b border-surface-border/40">
        <td colSpan={4} className="px-4 pb-2 pt-0">
          <Slot<SlotRowProps> name="ActionRowSlot" slotProps={slotProps} />
        </td>
      </tr>
    </>
  );
}

// ─── Skeleton loader ──────────────────────────────────────────────────────────

function SkeletonRow() {
  return (
    <tr className="border-b border-surface-border animate-pulse">
      {[48, 96, 24, 20].map((w, i) => (
        <td key={i} className="px-4 py-3">
          <div className={`h-4 bg-surface-overlay rounded w-${w}`} />
        </td>
      ))}
    </tr>
  );
}

// ─── Cabecera con KPIs ────────────────────────────────────────────────────────

function TableHeader({ plan }: { plan: RebalancePlan }) {
  const headerSlotProps: SlotHeaderProps = {
    totalValue: plan.totalValue,
    baseCurrency: plan.baseCurrency,
  };

  return (
    <div className="flex flex-col gap-4 mb-6">
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div>
          <h2 className="text-xl font-semibold text-fg">Posiciones de Cartera</h2>
          <p className="text-sm text-gray-500 mt-0.5">
            Portfolio{" "}
            <span className="font-mono text-gray-400">{plan.portfolioId}</span>
            {" · "}Cliente{" "}
            <span className="font-mono text-gray-400">{plan.clientId}</span>
          </p>
        </div>
        <div className="text-right">
          <p className="text-xs text-gray-600">Valoración</p>
          <p className="text-sm text-gray-400">
            {new Date(plan.asOf).toLocaleDateString("es-ES", {
              day: "2-digit", month: "long", year: "numeric",
            })}
          </p>
        </div>
      </div>

      {/* KPIs principales */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
        <KpiCard label="Valor Total" value={fmtEur(plan.totalValue)} accent="neutral" />
        {/* Schema 1.1: coste total y P&L no realizado calculados por el backend */}
        {plan.performance && (
          <>
            <KpiCard label="Coste Total" value={fmtEur(plan.performance.totalCost)} accent="neutral" />
            <KpiCard
              label="P&L No Realizado"
              value={fmtEur(plan.performance.unrealizedPnLAmount)}
              sub={fmtPnlPct(plan.performance.unrealizedPnLPercent)}
              accent={plan.performance.unrealizedPnLAmount >= 0 ? "gain" : "loss"}
            />
          </>
        )}
        <KpiCard label="Efectivo Actual" value={fmtEur(plan.cashFlow.cashBefore)} accent="neutral" />
        <KpiCard
          label="Efectivo Tras Rebalanceo"
          value={fmtEur(plan.cashFlow.cashAfter)}
          sub={plan.cashFlow.netCashFlow >= 0 ? `+${fmtEur(plan.cashFlow.netCashFlow)}` : fmtEur(plan.cashFlow.netCashFlow)}
          accent={plan.cashFlow.netCashFlow >= 0 ? "gain" : "warning"}
        />
        <KpiCard
          label="Órdenes"
          value={`${plan.orders.length} operaciones`}
          sub={`${plan.orders.filter(o => o.side === "BUY").length} compras · ${plan.orders.filter(o => o.side === "SELL").length} ventas`}
          accent="neutral"
        />
      </div>

      {/* ── SLOT: HeaderKpiSlot ─────────────────────────────────────────────
          Módulo 2 → KPI de ahorro fiscal potencial
          Si nadie registra nada → invisible
      ─────────────────────────────────────────────────────────────────── */}
      <Slot<SlotHeaderProps> name="HeaderKpiSlot" slotProps={headerSlotProps} />
    </div>
  );
}

function KpiCard({ label, value, sub, accent }: {
  label: string; value: string; sub?: string;
  accent: "gain" | "loss" | "neutral" | "warning";
}) {
  const c = { gain: "text-semantic-gain", loss: "text-semantic-loss", warning: "text-semantic-warning", neutral: "text-fg" }[accent];
  return (
    <div className="bg-surface-raised border border-surface-border rounded-lg px-4 py-3">
      <p className="text-xs text-gray-500 mb-1">{label}</p>
      <p className={`text-base font-semibold font-mono ${c}`}>{value}</p>
      {sub && <p className={`text-xs mt-0.5 ${c} opacity-80`}>{sub}</p>}
    </div>
  );
}

// ─── Panel de flujo de caja ───────────────────────────────────────────────────

function CashFlowPanel({ cashFlow }: { cashFlow: CashFlowSummary }) {
  return (
    <div className="bg-surface-raised border border-surface-border rounded-xl px-5 py-4 mt-4">
      <h3 className="text-sm font-semibold text-gray-400 mb-3">Flujo de Caja del Rebalanceo</h3>
      <div className="flex flex-wrap gap-6 text-sm">
        <div>
          <p className="text-xs text-gray-600">Efectivo inicial</p>
          <p className="font-mono text-fg">{fmtEur(cashFlow.cashBefore)}</p>
        </div>
        <div>
          <p className="text-xs text-gray-600">Total ventas</p>
          <p className="font-mono text-semantic-gain">+{fmtEur(cashFlow.sellTotal)}</p>
        </div>
        <div>
          <p className="text-xs text-gray-600">Total compras</p>
          <p className="font-mono text-semantic-loss">-{fmtEur(cashFlow.buyTotal)}</p>
        </div>
        <div>
          <p className="text-xs text-gray-600">Flujo neto</p>
          <p className={`font-mono font-semibold ${cashFlow.netCashFlow >= 0 ? "text-semantic-gain" : "text-semantic-loss"}`}>
            {cashFlow.netCashFlow >= 0 ? "+" : ""}{fmtEur(cashFlow.netCashFlow)}
          </p>
        </div>
        <div>
          <p className="text-xs text-gray-600">Efectivo final</p>
          <p className="font-mono text-fg font-semibold">{fmtEur(cashFlow.cashAfter)}</p>
        </div>
      </div>
    </div>
  );
}

// ─── Componente principal ─────────────────────────────────────────────────────

export function PositionsTableWithSlots({ plan, isLoading = false, error = null }: PositionsTableProps) {
  if (error) {
    return (
      <div className="flex flex-col items-center justify-center py-20 gap-3">
        <div className="w-12 h-12 rounded-full bg-semantic-loss/15 flex items-center justify-center">
          <span className="text-semantic-loss text-xl" aria-hidden="true">!</span>
        </div>
        <p className="text-semantic-loss font-medium">Error cargando posiciones</p>
        <p className="text-gray-500 text-sm text-center max-w-sm">{error}</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-0">
      {/* Cabecera */}
      {plan && <TableHeader plan={plan} />}

      {/* Tabla */}
      <div className="bg-surface-raised border border-surface-border rounded-xl overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm" aria-label="Tabla de posiciones de cartera">
            <thead>
              <tr className="border-b border-surface-border bg-surface-overlay/50">
                <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">Activo</th>
                <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider min-w-[160px]">Peso · Delta</th>
                <th className="px-4 py-3 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">Valor · Orden</th>
                <th className="px-4 py-3 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">P&amp;L / Movimiento</th>
              </tr>
            </thead>
            <tbody>
              {isLoading
                ? Array.from({ length: 4 }).map((_, i) => <SkeletonRow key={i} />)
                : (plan?.positions ?? []).map((pos, i) => (
                    <PositionRow key={pos.instrumentId} position={pos} isEven={i % 2 === 0} />
                  ))}
            </tbody>
            {!isLoading && plan && plan.positions.length > 0 && (
              <tfoot>
                <tr className="border-t-2 border-surface-border bg-surface-overlay/30">
                  <td className="px-4 py-3 text-xs text-gray-500 font-semibold uppercase tracking-wider">
                    Total — {plan.positions.length} posiciones
                  </td>
                  <td className="px-4 py-3" />
                  <td className="px-4 py-3 text-right font-mono font-semibold text-fg text-sm">
                    {fmtEur(plan.totalValue)}
                  </td>
                  <td className="px-4 py-3" />
                </tr>
              </tfoot>
            )}
          </table>
        </div>
        {!isLoading && (!plan || plan.positions.length === 0) && (
          <div className="flex flex-col items-center justify-center py-16 gap-2">
            <p className="text-gray-500 font-medium">No hay posiciones registradas</p>
          </div>
        )}
      </div>

      {/* Panel de flujo de caja */}
      {!isLoading && plan && <CashFlowPanel cashFlow={plan.cashFlow} />}
    </div>
  );
}
