"use client";

/**
 * PositionsTableWithSlots.tsx — Tabla maestra de posiciones del portfolio
 *
 * RESPONSABILIDAD (Módulo 1 — Balanceador Core):
 *   Renderizar las posiciones de custodia del cliente con sus datos básicos:
 *   nombre, clase de activo, peso actual vs. objetivo, valor de mercado y P&L.
 *
 * EXTENSIBILIDAD (Patrón de Slots):
 *   Esta tabla NO conoce ni importa nada de los Módulos 2, 3 o 4.
 *   Los módulos futuros se "enchufan" registrando sus componentes en el SlotRegistry.
 *   Los huecos reservados son:
 *
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │  [HeaderKpiSlot]  ← KPIs extra en la cabecera (Módulo 2: tax-loss) │
 *   ├──────┬──────────────────────────────────────┬───────────────────────┤
 *   │ ISIN │  Nombre / Clase  [StatusBadgeSlot]   │ Peso | Valor | P&L   │
 *   ├──────┴──────────────────────────────────────┴───────────────────────┤
 *   │  [ActionRowSlot]  ← acciones extra por fila (Módulo 2: TLH button) │
 *   └─────────────────────────────────────────────────────────────────────┘
 *
 * REGLA: No importar ni referenciar nada de /mod2-tax-ui, /mod3-irpf-ui
 *        o /mod4-pledge-ui en este archivo. NUNCA.
 *
 * @module mod1-rebalancer-ui/PositionsTableWithSlots
 */

import React from "react";
import type { AssetPosition, AssetClass, PortfolioMeta } from "@/core-ui/types/portfolio";
import { Slot } from "@/core-ui/SlotRegistry";
import type { SlotRowProps, SlotHeaderProps } from "@/core-ui/SlotRegistry";

// ─── Tipos ───────────────────────────────────────────────────────────────────

export interface PositionsTableProps {
  meta: PortfolioMeta;
  positions: AssetPosition[];
  /** Estado de carga: muestra skeletons si true */
  isLoading?: boolean;
  /** Mensaje de error a mostrar si la carga falló */
  error?: string | null;
}

// ─── Helpers de formateo ─────────────────────────────────────────────────────

const EUR_FORMATTER = new Intl.NumberFormat("es-ES", {
  style: "currency",
  currency: "EUR",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const PCT_FORMATTER = new Intl.NumberFormat("es-ES", {
  style: "percent",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

function formatCurrency(value: number): string {
  return EUR_FORMATTER.format(value);
}

function formatPct(value: number): string {
  // value es 0–1 (ej: 0.0409 → "4,09 %")
  return PCT_FORMATTER.format(value);
}

function formatPnlPct(value: number): string {
  // value es un porcentaje absoluto (ej: 19.93 → "+19,93 %")
  const sign = value >= 0 ? "+" : "";
  return `${sign}${value.toFixed(2).replace(".", ",")} %`;
}

// ─── Colores por clase de activo ─────────────────────────────────────────────

const ASSET_CLASS_STYLES: Record<AssetClass, { bg: string; text: string; label: string }> = {
  EQUITY:       { bg: "bg-blue-500/15",   text: "text-blue-400",   label: "Renta Variable" },
  FIXED_INCOME: { bg: "bg-teal-500/15",   text: "text-teal-400",   label: "Renta Fija" },
  CASH:         { bg: "bg-gray-500/15",   text: "text-gray-400",   label: "Efectivo" },
  ALTERNATIVE:  { bg: "bg-orange-500/15", text: "text-orange-400", label: "Alternativo" },
  REAL_ESTATE:  { bg: "bg-yellow-500/15", text: "text-yellow-400", label: "Inmobiliario" },
};

// ─── Sub-componente: Badge de clase de activo ─────────────────────────────────

function AssetClassBadge({ assetClass }: { assetClass: AssetClass }) {
  const style = ASSET_CLASS_STYLES[assetClass] ?? ASSET_CLASS_STYLES.ALTERNATIVE;
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${style.bg} ${style.text}`}
    >
      {style.label}
    </span>
  );
}

// ─── Sub-componente: Barra de peso (actual vs. objetivo) ─────────────────────

interface WeightBarProps {
  current: number;   // 0–1
  target: number;    // 0–1
}

function WeightBar({ current, target }: WeightBarProps) {
  const deviation = current - target;
  const isOver = deviation > 0.005;
  const isUnder = deviation < -0.005;
  const barColor = isOver
    ? "bg-semantic-warning"
    : isUnder
      ? "bg-blue-500"
      : "bg-semantic-gain";

  return (
    <div className="flex flex-col gap-1 min-w-[100px]">
      <div className="flex justify-between text-xs">
        <span className={isOver ? "text-semantic-warning" : isUnder ? "text-blue-400" : "text-semantic-gain"}>
          {formatPct(current)}
        </span>
        <span className="text-gray-500">
          obj. {formatPct(target)}
        </span>
      </div>
      {/* Barra compuesta: fondo gris (objetivo), relleno de color (actual) */}
      <div className="relative h-1.5 bg-surface-overlay rounded-full overflow-hidden w-full">
        {/* Marcador de objetivo */}
        <div
          className="absolute top-0 bottom-0 w-0.5 bg-gray-500 z-10"
          style={{ left: `${Math.min(target * 100, 100)}%` }}
          title={`Objetivo: ${formatPct(target)}`}
        />
        {/* Barra de peso actual */}
        <div
          className={`h-full rounded-full transition-all duration-300 ${barColor}`}
          style={{ width: `${Math.min(current * 100, 100)}%` }}
        />
      </div>
    </div>
  );
}

// ─── Sub-componente: Celda de P&L ────────────────────────────────────────────

function PnlCell({ unrealized, unrealizedPct }: { unrealized: number; unrealizedPct: number }) {
  const isPositive = unrealized >= 0;
  const colorClass = isPositive ? "text-semantic-gain" : "text-semantic-loss";

  return (
    <div className="flex flex-col items-end">
      <span className={`text-sm font-medium ${colorClass}`}>
        {unrealized === 0 ? "—" : formatCurrency(unrealized)}
      </span>
      {unrealizedPct !== 0 && (
        <span className={`text-xs ${colorClass} opacity-80`}>
          {formatPnlPct(unrealizedPct)}
        </span>
      )}
    </div>
  );
}

// ─── Sub-componente: Fila de posición ────────────────────────────────────────

interface PositionRowProps {
  position: AssetPosition;
  isEven: boolean;
}

function PositionRow({ position, isEven }: PositionRowProps) {
  const rowSlotProps: SlotRowProps = { position };

  return (
    <>
      <tr
        className={[
          "border-b border-surface-border",
          "hover:bg-surface-overlay/50 transition-colors duration-100",
          isEven ? "bg-surface-base/30" : "",
        ].join(" ")}
      >
        {/* Columna: Ticker / Nombre / Clase */}
        <td className="px-4 py-3">
          <div className="flex flex-col gap-1">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-mono text-sm text-white font-semibold">
                {position.ticker}
              </span>
              <AssetClassBadge assetClass={position.assetClass} />
              {/* ── SLOT: StatusBadgeSlot ────────────────────────────────
                  Los módulos futuros inyectan aquí sus badges de estado.
                  Ejemplos: TwoMonthWarningBadge (Mod2), PledgedLockIcon (Mod4).
                  Si nadie registra nada → este slot renderiza null silenciosamente.
              ─────────────────────────────────────────────────────────── */}
              <Slot<SlotRowProps>
                name="StatusBadgeSlot"
                slotProps={rowSlotProps}
              />
            </div>
            <span className="text-xs text-gray-400 truncate max-w-xs" title={position.name}>
              {position.name}
            </span>
            <span className="text-xs text-gray-600 font-mono">
              {position.isin !== "CASH_EUR" ? position.isin : "—"}
            </span>
          </div>
        </td>

        {/* Columna: Peso actual vs. objetivo */}
        <td className="px-4 py-3">
          <WeightBar current={position.currentWeight} target={position.targetWeight} />
        </td>

        {/* Columna: Valor de mercado */}
        <td className="px-4 py-3 text-right">
          <div className="flex flex-col items-end">
            <span className="text-sm text-white font-medium">
              {formatCurrency(position.currentValue)}
            </span>
            <span className="text-xs text-gray-500">
              {position.quantity.toLocaleString("es-ES")} uds. · {position.currency}
            </span>
          </div>
        </td>

        {/* Columna: P&L no realizado */}
        <td className="px-4 py-3">
          <PnlCell
            unrealized={position.pnl.unrealized}
            unrealizedPct={position.pnl.unrealizedPct}
          />
        </td>
      </tr>

      {/* ── SLOT: ActionRowSlot ────────────────────────────────────────────
          Fila extra debajo de cada posición para acciones adicionales.
          Ejemplos: botón "Tax-Loss Harvesting" (Mod2), selector de lotes (Mod3).
          Renderiza una celda que abarca todas las columnas.
          Si nadie registra nada → esta fila no se renderiza en absoluto.
      ─────────────────────────────────────────────────────────────────── */}
      <ActionRowSlotWrapper position={position} />
    </>
  );
}

/** Envuelve el ActionRowSlot en una fila de tabla solo si hay contenido registrado */
function ActionRowSlotWrapper({ position }: { position: AssetPosition }) {
  const slotProps: SlotRowProps = { position };
  return (
    <tr className="border-b border-surface-border/40">
      <td colSpan={4} className="px-4 pb-2 pt-0">
        <Slot<SlotRowProps>
          name="ActionRowSlot"
          slotProps={slotProps}
        />
      </td>
    </tr>
  );
}

// ─── Sub-componente: Skeleton loader ─────────────────────────────────────────

function SkeletonRow() {
  return (
    <tr className="border-b border-surface-border animate-pulse">
      <td className="px-4 py-3">
        <div className="flex flex-col gap-2">
          <div className="h-4 bg-surface-overlay rounded w-20" />
          <div className="h-3 bg-surface-overlay rounded w-48" />
          <div className="h-3 bg-surface-overlay rounded w-32" />
        </div>
      </td>
      <td className="px-4 py-3">
        <div className="h-4 bg-surface-overlay rounded w-full" />
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-col items-end gap-1">
          <div className="h-4 bg-surface-overlay rounded w-24" />
          <div className="h-3 bg-surface-overlay rounded w-16" />
        </div>
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-col items-end gap-1">
          <div className="h-4 bg-surface-overlay rounded w-20" />
          <div className="h-3 bg-surface-overlay rounded w-14" />
        </div>
      </td>
    </tr>
  );
}

// ─── Sub-componente: Cabecera con KPI summary ────────────────────────────────

interface TableHeaderProps {
  meta: PortfolioMeta;
}

function TableHeader({ meta }: TableHeaderProps) {
  const isPositivePnl = meta.totalUnrealizedPnl >= 0;
  const headerSlotProps: SlotHeaderProps = {
    totalValue: meta.totalMarketValue,
    baseCurrency: meta.baseCurrency,
  };

  return (
    <div className="flex flex-col gap-4 mb-6">
      {/* Título y fecha de valoración */}
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div>
          <h2 className="text-xl font-semibold text-white">
            Posiciones de Custodia
          </h2>
          <p className="text-sm text-gray-500 mt-0.5">
            {meta.clientName} · Portfolio{" "}
            <span className="font-mono text-gray-400">{meta.portfolioId}</span>
          </p>
        </div>
        <div className="text-right">
          <p className="text-xs text-gray-600">Valoración</p>
          <p className="text-sm text-gray-400">
            {new Date(meta.valuationDate).toLocaleDateString("es-ES", {
              day: "2-digit",
              month: "long",
              year: "numeric",
            })}
          </p>
        </div>
      </div>

      {/* KPIs principales */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <KpiCard
          label="Valor Total"
          value={formatCurrency(meta.totalMarketValue)}
          accent="neutral"
        />
        <KpiCard
          label="Coste Total"
          value={formatCurrency(meta.totalCostBasis)}
          accent="neutral"
        />
        <KpiCard
          label="P&L No Realizado"
          value={formatCurrency(meta.totalUnrealizedPnl)}
          sub={formatPnlPct(meta.totalUnrealizedPnlPct)}
          accent={isPositivePnl ? "gain" : "loss"}
        />
        <KpiCard
          label="Divisa Base"
          value={meta.baseCurrency}
          accent="neutral"
        />
      </div>

      {/* ── SLOT: HeaderKpiSlot ─────────────────────────────────────────────
          KPIs adicionales de módulos futuros se inyectan aquí.
          Ejemplo: "Ahorro fiscal potencial" del Módulo 2.
          Si nadie registra nada → este slot no renderiza nada.
      ─────────────────────────────────────────────────────────────────── */}
      <Slot<SlotHeaderProps>
        name="HeaderKpiSlot"
        slotProps={headerSlotProps}
      />
    </div>
  );
}

// ─── Sub-componente: KPI Card ─────────────────────────────────────────────────

interface KpiCardProps {
  label: string;
  value: string;
  sub?: string;
  accent: "gain" | "loss" | "neutral" | "warning";
}

function KpiCard({ label, value, sub, accent }: KpiCardProps) {
  const accentClass = {
    gain:    "text-semantic-gain",
    loss:    "text-semantic-loss",
    warning: "text-semantic-warning",
    neutral: "text-white",
  }[accent];

  return (
    <div className="bg-surface-raised border border-surface-border rounded-lg px-4 py-3">
      <p className="text-xs text-gray-500 mb-1">{label}</p>
      <p className={`text-base font-semibold font-mono ${accentClass}`}>{value}</p>
      {sub && (
        <p className={`text-xs mt-0.5 ${accentClass} opacity-80`}>{sub}</p>
      )}
    </div>
  );
}

// ─── Componente principal: PositionsTableWithSlots ───────────────────────────

export function PositionsTableWithSlots({
  meta,
  positions,
  isLoading = false,
  error = null,
}: PositionsTableProps) {
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
      {/* Cabecera con KPIs */}
      <TableHeader meta={meta} />

      {/* Contenedor de la tabla */}
      <div className="bg-surface-raised border border-surface-border rounded-xl overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm" aria-label="Tabla de posiciones de custodia">
            {/* Cabecera de columnas */}
            <thead>
              <tr className="border-b border-surface-border bg-surface-overlay/50">
                <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                  Activo
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider min-w-[160px]">
                  Peso Actual / Objetivo
                </th>
                <th className="px-4 py-3 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">
                  Valor Mercado
                </th>
                <th className="px-4 py-3 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">
                  P&amp;L No Realizado
                </th>
              </tr>
            </thead>

            {/* Cuerpo */}
            <tbody>
              {isLoading
                ? Array.from({ length: 4 }).map((_, i) => (
                    <SkeletonRow key={`skeleton-${i}`} />
                  ))
                : positions.map((position, index) => (
                    <PositionRow
                      key={position.isin}
                      position={position}
                      isEven={index % 2 === 0}
                    />
                  ))}
            </tbody>

            {/* Footer con totales */}
            {!isLoading && positions.length > 0 && (
              <tfoot>
                <tr className="border-t-2 border-surface-border bg-surface-overlay/30">
                  <td className="px-4 py-3 text-xs text-gray-500 font-semibold uppercase tracking-wider">
                    Total — {positions.length} posiciones
                  </td>
                  <td className="px-4 py-3" />
                  <td className="px-4 py-3 text-right">
                    <span className="text-sm font-semibold text-white font-mono">
                      {formatCurrency(meta.totalMarketValue)}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <PnlCell
                      unrealized={meta.totalUnrealizedPnl}
                      unrealizedPct={meta.totalUnrealizedPnlPct}
                    />
                  </td>
                </tr>
              </tfoot>
            )}
          </table>
        </div>

        {/* Estado vacío */}
        {!isLoading && positions.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 gap-2">
            <p className="text-gray-500 font-medium">No hay posiciones registradas</p>
            <p className="text-gray-600 text-sm">
              Importa datos desde un bróker para comenzar.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
